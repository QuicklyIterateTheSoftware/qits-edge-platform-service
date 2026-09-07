package eu.wohlben.qits.edge;

import eu.wohlben.qits.eventstream.control.CatchupResult;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * Reads the project lifecycle log to its head at startup, then asks for one certificate reconcile.
 *
 * <p><b>It is deliberately not part of {@link DeploymentProjectionBootstrap}.</b> That coordinator
 * holds the edge's whole READINESS down until the routing projection is complete, because routing
 * any request against a stale projection sends it to the wrong process. This one does not, because
 * the consequence here is narrower — but it is no longer only a certificate. The slug set became a
 * ROUTING input when the grammar grew its project tiers, so a projection that is behind reads two
 * shapes of name wrongly, and {@link #authoritative()} is what the router asks before it answers
 * either of them 404. That is a 503 on the handful of names whose reading could still change,
 * rather than a refusal of every request the edge has: see {@link
 * HostEnvironments#projectSensitive(String, java.util.Set)}.
 *
 * <p><b>A restarted edge with data serves normally throughout.</b> {@code edge_project} is
 * persisted, so the slugs of a platform that has been running are already loaded before the first
 * request; the only names the barrier touches are the ones that would have 404'd anyway. The window
 * is a genuinely behind projection — a wiped database, or a first boot.
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

  private final AtomicBoolean caughtUp = new AtomicBoolean();

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
      // Trusted by explicit configuration, exactly as the routing projection is: a suite with no
      // qits-events to talk to must not answer every project-shaped name 503 for its whole run.
      caughtUp.set(true);
      LOG.warn(
          "project SAN catch-up is disabled; the certificate covers the configured names only");
      return;
    }
    Thread.ofVirtual().name("edge-project-sans-catchup").start(this::catchUpAndRequestReconcile);
  }

  /**
   * Whether the slug set has been read through a confirmed qits-events head, so a name it does not
   * know is a name that does not exist rather than one that has not arrived.
   */
  public boolean authoritative() {
    return caughtUp.get();
  }

  /**
   * Package-visible so a suite can put the barrier back up and take it down again; production only
   * ever raises it, once, from {@link #catchUpAndRequestReconcile()}.
   */
  void authoritative(boolean value) {
    caughtUp.set(value);
  }

  void catchUpAndRequestReconcile() {
    // From the epoch, and for the same reason the routing bootstrap rewinds: the edge database may
    // have been wiped while qits-eventstream's watermark survived, and an ordinary catch-up would
    // then start after it and faithfully rebuild nothing. EdgeProjects' last-writer-wins keeps a
    // replay over a surviving projection harmless, so there is no truncate before this call.
    CatchupResult result = catchup.rebuildFromEpoch(ProjectLifecycleSubscriber.CONSUMER_ID);
    while (true) {
      if (result.reachedLogHead()) {
        // The routing barrier comes down first and the order is requested second: a request that
        // arrives between the two must find the slug set complete, and a reconcile takes minutes.
        caughtUp.set(true);
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
