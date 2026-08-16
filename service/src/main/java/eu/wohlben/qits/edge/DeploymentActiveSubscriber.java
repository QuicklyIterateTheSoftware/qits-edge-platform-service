package eu.wohlben.qits.edge;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/** Durable projection of qits-deployments' successful deployment event. */
@ApplicationScoped
public class DeploymentActiveSubscriber implements QitsDurableEventListener {

  static final String SIGNATURE = "DeploymentActive";
  static final String CONSUMER_ID = "edge-active-endpoints";

  private static final Logger LOG = Logger.getLogger(DeploymentActiveSubscriber.class);

  /**
   * Private wire DTO rather than a dependency on qits-deployments' event jar. The contract is
   * cross-repository JSON and additions must not force the edge to wait for a Maven release.
   * Missing {@code endpoints} means an older event and is deliberately ignored; an explicit empty
   * array is a real snapshot that removes the application's old routes.
   */
  record DeploymentActivePayload(
      String applicationName, String environmentName, List<EndpointPayload> endpoints) {}

  /**
   * The canonical endpoint shape published by qits-deployments. Unknown future fields are ignored.
   */
  record EndpointPayload(
      String path,
      String upstreamHost,
      Integer upstreamPort,
      String navigationLabel,
      Integer navigationPosition) {}

  @Inject EdgeRoutes routes;
  @Inject EdgeConfig config;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  /**
   * The projection has no other seed. A new edge must read the deployment history from its first
   * event, rather than initializing its watermark at today's head and having no routes until every
   * service is deployed again.
   */
  @Override
  public boolean replayFromEpoch() {
    return true;
  }

  @Override
  public void onFrame(EventFrame frame) {
    DeploymentActivePayload active = decode(frame);
    if (active == null || active.endpoints() == null) {
      return;
    }
    try {
      List<String> environments =
          active.environmentName() == null || active.environmentName().isBlank()
              ? config.environments()
              : List.of(active.environmentName());
      for (String environment : environments) {
        List<EdgeEndpoint> endpoints = new ArrayList<>();
        for (EndpointPayload endpoint : active.endpoints()) {
          if (endpoint == null || endpoint.upstreamPort() == null) {
            throw new IllegalArgumentException("An endpoint has no upstream port.");
          }
          endpoints.add(
              new EdgeEndpoint(
                  environment,
                  active.applicationName(),
                  endpoint.path(),
                  new Upstream(endpoint.upstreamHost(), endpoint.upstreamPort()),
                  endpoint.navigationLabel(),
                  endpoint.navigationPosition()));
        }
        boolean replaced =
            routes.replace(
                environment, active.applicationName(), frame.id(), frame.occurredAt(), endpoints);
        if (replaced) {
          LOG.infof(
              "activated %d direct routes for %s in %s from DeploymentActive %s",
              endpoints.size(), active.applicationName(), environment, frame.id());
        }
      }
    } catch (IllegalArgumentException poison) {
      // An invalid route declaration will remain invalid on the next catch-up. Settling it keeps a
      // bad deployment from pinning this consumer's watermark before every later deployment.
      LOG.warnf(
          "%s %s carries an unusable endpoint snapshot: %s; it is settled without changing routes",
          frame.name(), frame.id(), poison.getMessage());
    }
  }

  private DeploymentActivePayload decode(EventFrame frame) {
    try {
      DeploymentActivePayload payload =
          CanonicalJson.payloadTo(frame.payload(), DeploymentActivePayload.class);
      if (payload.applicationName() == null || payload.applicationName().isBlank()) {
        LOG.warnf(
            "%s %s carries no applicationName; it is settled unhandled", frame.name(), frame.id());
        return null;
      }
      if (payload.endpoints() == null) {
        LOG.debugf(
            "%s %s predates endpoint snapshots; it leaves the current edge projection unchanged",
            frame.name(), frame.id());
      }
      return payload;
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "%s %s carried an unreadable DeploymentActive payload: %s; it is settled unhandled",
          frame.name(), frame.id(), unreadable.getMessage());
      return null;
    }
  }
}
