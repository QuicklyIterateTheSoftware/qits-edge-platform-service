package eu.wohlben.qits.edge;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
      String applicationName,
      String environmentName,
      String browserHost,
      List<EndpointPayload> endpoints,
      List<NavigationPayload> navigation) {}

  /**
   * The canonical endpoint shape published by qits-deployments. Unknown future fields are ignored.
   *
   * <p>{@code navigationLabel} and {@code navigationPosition} have LEFT the publisher and are kept
   * here on purpose: the log is replayed from the epoch on every start, so every frame ever
   * published is read again by this code and the old shape has to keep meaning what it meant.
   */
  record EndpointPayload(
      String path,
      String upstreamHost,
      Integer upstreamPort,
      String navigationLabel,
      Integer navigationPosition) {}

  /** One application-level navigation placement — see {@link EdgeRoutes#SLOTS}. */
  record NavigationPayload(String slot, String label, Integer position) {}

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
                  new Upstream(endpoint.upstreamHost(), endpoint.upstreamPort())));
        }
        EdgeRoutes.Snapshot snapshot =
            new EdgeRoutes.Snapshot(
                endpoints, browserHost(active, environment, endpoints), navigation(active));
        boolean replaced =
            routes.replace(
                environment, active.applicationName(), frame.id(), frame.occurredAt(), snapshot);
        if (replaced) {
          LOG.infof(
              "activated %d direct routes for %s in %s from DeploymentActive %s%s",
              endpoints.size(),
              active.applicationName(),
              environment,
              frame.id(),
              snapshot.browserHost() == null ? "" : ", host " + snapshot.browserHost());
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

  /**
   * The public name this application asked for, checked against everything it must not collide
   * with.
   *
   * <p>Three refusals, and each of them would otherwise be a name the edge cannot route or a name
   * it routes to the wrong process: a host that is not a DNS label, a host that is an ENVIRONMENT
   * name — {@code HostEnvironments} reads the first label as an application, so the environment
   * would become unreachable — and a host that is already a configured {@code qits.edge.apps} entry
   * for a DIFFERENT service. The last one is a match rather than a ban: {@code registry} is both a
   * configured vhost and the name qits-artifacts publishes, and they are the same service exactly
   * when the configured pattern resolves to the address the deployment published.
   */
  private String browserHost(
      DeploymentActivePayload active, String environment, List<EdgeEndpoint> endpoints) {
    String raw = active.browserHost();
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String host = raw.strip().toLowerCase(Locale.ROOT);
    if (!HostEnvironments.isLabel(host)) {
      throw new IllegalArgumentException(
          "`" + raw + "` cannot be a DNS label, so it cannot be a service host.");
    }
    for (String environmentName : config.environments()) {
      if (environmentName != null
          && host.equals(environmentName.strip().toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException(
            "`"
                + host
                + "` is an environment name. The first label reads as an application, so that"
                + " environment would stop being reachable by name.");
      }
    }
    EdgeConfig.App configured = config.apps().get(host);
    if (configured != null) {
      Upstream pattern = EdgeRouter.appUpstream(configured, environment);
      Upstream published = endpoints.isEmpty() ? null : endpoints.get(0).upstream();
      if (published == null
          || !pattern.host().equalsIgnoreCase(published.host())
          || pattern.port() != published.port()) {
        throw new IllegalArgumentException(
            "`"
                + host
                + "` is a configured application vhost pointing at "
                + pattern
                + ", and this deployment publishes it as "
                + published
                + ". The configured one is kept.");
      }
    }
    return host;
  }

  /**
   * The placements this frame carries, or the one an OLD frame means.
   *
   * <p>A pre-host frame carried the label on the primary endpoint and had nowhere else to put it.
   * It means "global", so it becomes one {@code system} placement — and no host, because an old
   * frame never named one and the edge must not invent a public name for a service that has not
   * been flipped.
   */
  private static List<EdgeRoutes.NavigationEntry> navigation(DeploymentActivePayload active) {
    List<EdgeRoutes.NavigationEntry> entries = new ArrayList<>();
    if (active.navigation() != null && !active.navigation().isEmpty()) {
      for (NavigationPayload placement : active.navigation()) {
        if (placement == null) {
          throw new IllegalArgumentException("A navigation placement is missing.");
        }
        entries.add(
            new EdgeRoutes.NavigationEntry(
                placement.slot(),
                placement.label(),
                placement.position() == null ? 1 : placement.position()));
      }
      return entries;
    }
    for (EndpointPayload endpoint : active.endpoints()) {
      if (endpoint.navigationLabel() == null) {
        continue;
      }
      if (!entries.isEmpty()) {
        throw new IllegalArgumentException("One application may publish one navigation route.");
      }
      entries.add(
          new EdgeRoutes.NavigationEntry(
              EdgeRoutes.SYSTEM_SLOT,
              endpoint.navigationLabel(),
              endpoint.navigationPosition() == null ? 1 : endpoint.navigationPosition()));
    }
    return entries;
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
