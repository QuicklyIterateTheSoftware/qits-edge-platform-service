package eu.wohlben.qits.edge;

import eu.wohlben.qits.eventstream.control.CatchupResult;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import org.jboss.logging.Logger;

/**
 * Reads the project lifecycle log to its head at startup, then asks for one certificate reconcile.
 *
 * <p><b>It is deliberately not part of {@link DeploymentProjectionBootstrap}.</b> That coordinator
 * holds the edge's READINESS down until the routing projection is complete, because routing a
 * request against a stale projection sends it to the wrong process. Nothing here is of that kind: a
 * missing slug costs one project's editor host a certificate until the next order, and folding this
 * into the readiness barrier would mean an edge that refuses every request because it has not
 * finished its certificate bookkeeping. The two projections recover independently, which is what
 * their consequences deserve.
 *
 * <p><b>The reconcile at the end closes a boot race.</b> {@link EdgeCertificateManager} reconciles
 * on its own {@code StartupEvent}, and on a fresh edge — or after the edge database is wiped — that
 * runs while this catch-up is still replaying, so it computes a desired set with no projects in it
 * and finds nothing due. One request once the head is reached is what turns that into the whole
 * set; it is debounced like every other, so a burst of replayed creates and this call are one
 * order.
 *
 * <p>Retries indefinitely, for the same reason the routing bootstrap does: qits-events being
 * briefly down must not leave the certificate permanently short of a tier.
 */
@ApplicationScoped
public class ProjectSansBootstrap {

  private static final Logger LOG = Logger.getLogger(ProjectSansBootstrap.class);

  @Inject DeploymentProjectionCatchup catchup;
  @Inject EdgeConfig config;
  @Inject EdgeCertificateManager certificates;

  /** Test-only escape hatch for suites that deliberately turn qits-eventstream off. */
  boolean required;

  Duration retry;

  ProjectSansBootstrap() {}

  ProjectSansBootstrap(
      DeploymentProjectionCatchup catchup,
      EdgeCertificateManager certificates,
      boolean required,
      Duration retry) {
    this.catchup = catchup;
    this.certificates = certificates;
    this.required = required;
    this.retry = retry;
  }

  void start(@Observes StartupEvent ignored) {
    // The same knob the routing projection reads. A suite that has no qits-events to talk to would
    // otherwise spin a virtual thread retrying against nothing for the length of the build.
    required = config.projection().catchup().required();
    retry = config.projection().catchup().retry();
    if (!required) {
      LOG.warn(
          "project SAN catch-up is disabled; the certificate covers the configured names only");
      return;
    }
    Thread.ofVirtual().name("edge-project-sans-catchup").start(this::catchUpAndRequestReconcile);
  }

  void catchUpAndRequestReconcile() {
    // From the epoch, and for the same reason the routing bootstrap rewinds: the edge database may
    // have been wiped while qits-eventstream's watermark survived, and an ordinary catch-up would
    // then start after it and faithfully rebuild nothing. EdgeProjects' last-writer-wins keeps a
    // replay over a surviving projection harmless, so there is no truncate before this call.
    CatchupResult result = catchup.rebuildFromEpoch(ProjectLifecycleSubscriber.CONSUMER_ID);
    while (true) {
      if (result.reachedLogHead()) {
        LOG.infof(
            "project SAN projection caught up to qits-events (%d frame(s) handled)",
            result.handled());
        certificates.requestReconcile();
        return;
      }
      LOG.warnf("project SAN projection is not ready (%s); retrying in %s", result.status(), retry);
      if (!waitForRetry()) {
        return;
      }
      result = catchup.catchUp(ProjectLifecycleSubscriber.CONSUMER_ID);
    }
  }

  private boolean waitForRetry() {
    try {
      Thread.sleep(retry);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      LOG.warn("project SAN catch-up was interrupted; the certificate keeps the names it has");
      return false;
    }
  }
}
