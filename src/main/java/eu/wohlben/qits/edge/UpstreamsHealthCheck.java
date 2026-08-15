package eu.wohlben.qits.edge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness: this edge has rebuilt its deployment routing projection.
 *
 * <p>It deliberately does not probe application endpoints: an edge whose downstream is down is
 * still correctly configured, and restarting it for that outage would make recovery worse.
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
    response.withData("default", router.defaultEnvironment());
    return response.build();
  }
}
