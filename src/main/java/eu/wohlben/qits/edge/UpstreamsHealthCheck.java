package eu.wohlben.qits.edge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness: this edge knows where to send traffic.
 *
 * <p>It reports the resolved environment to upstream map as health data, which is the fastest way
 * to read what a running edge believes — the same trick qits-gateway's route-table check plays. It
 * deliberately does <b>not</b> probe the gateways: an edge whose environments are all down is still
 * correctly configured and still the right process to be running, and an orchestrator that
 * restarted it for a downstream outage would take the last working environment down with it.
 *
 * <p>The check cannot fail today — {@link HostEnvironments} refuses an empty environment list at
 * startup, so a booted edge always has at least one upstream. That is on purpose: the failure is
 * moved to boot, where it is one loud message, rather than being left to a probe that reports DOWN
 * without saying why.
 */
@Readiness
@ApplicationScoped
public class UpstreamsHealthCheck implements HealthCheck {

  @Inject EdgeRouter router;

  @Inject DeploymentProjectionBootstrap projectionBootstrap;

  @Override
  public HealthCheckResponse call() {
    HealthCheckResponseBuilder response =
        HealthCheckResponse.named("edge upstreams")
            .status(projectionBootstrap.authoritative())
            .withData("deployment-projection", projectionBootstrap.state());
    router
        .upstreams()
        .forEach((environment, upstream) -> response.withData(environment, upstream.toString()));
    response.withData("default", router.defaultEnvironment());
    return response.build();
  }
}
