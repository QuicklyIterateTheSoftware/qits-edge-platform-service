package eu.wohlben.qits.edge;

import eu.wohlben.qits.eventstream.control.CatchupResult;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.logging.Logger;

/**
 * Makes the deployment projection authoritative only after its durable consumer has read the log to
 * a confirmed head.
 *
 * <p>PostgreSQL is a cache of this projection, not proof that it is current: a newer {@code
 * DeploymentActive} can have been published while this edge was down. At startup this coordinator
 * therefore holds readiness down and keeps direct path routing disabled until the eventstream
 * reader reports that {@link DeploymentActiveSubscriber#CONSUMER_ID} reached a page whose explicit
 * next cursor was absent. It retries indefinitely: qits-events being briefly down must not turn a
 * recoverable boot race into a permanently stale edge.
 */
@ApplicationScoped
public class DeploymentProjectionBootstrap {

  private static final Logger LOG = Logger.getLogger(DeploymentProjectionBootstrap.class);

  enum State {
    CATCHING_UP,
    READY
  }

  @jakarta.inject.Inject DeploymentProjectionCatchup catchup;
  @jakarta.inject.Inject EdgeConfig config;

  /** Test-only escape hatch for suites that deliberately turn qits-eventstream off. */
  boolean required;

  Duration retry;

  private final AtomicReference<State> state = new AtomicReference<>(State.CATCHING_UP);

  DeploymentProjectionBootstrap() {}

  DeploymentProjectionBootstrap(
      DeploymentProjectionCatchup catchup, boolean required, Duration retry) {
    this.catchup = catchup;
    this.required = required;
    this.retry = retry;
  }

  void start(@Observes StartupEvent ignored) {
    required = config.projection().catchup().required();
    retry = config.projection().catchup().retry();
    if (!required) {
      state.set(State.READY);
      LOG.warn(
          "deployment projection catch-up is disabled; routes are trusted by explicit configuration");
      return;
    }
    Thread.ofVirtual().name("edge-deployment-projection-catchup").start(this::catchUpUntilReady);
  }

  /** Whether direct routes and edge-owned navigation are backed by a complete projection. */
  public boolean authoritative() {
    return state.get() == State.READY;
  }

  /**
   * A stable readiness data value, useful without exposing eventstream's implementation details.
   */
  public String state() {
    return state.get().name().toLowerCase().replace('_', '-');
  }

  void catchUpUntilReady() {
    // The edge projection may have been wiped while qits-eventstream's consumer watermark and
    // claims survived. An ordinary catch-up would then start after that watermark and faithfully
    // rebuild nothing. Resetting this replay-from-epoch consumer at every edge boot makes the log,
    // rather than either local database, the reconstruction source. EdgeRoutes' LWW replacement
    // makes replay over a surviving snapshot safe too, so there is deliberately no projection
    // truncate before this call.
    CatchupResult result = catchup.rebuildFromEpoch(DeploymentActiveSubscriber.CONSUMER_ID);
    while (!authoritative()) {
      if (result.reachedLogHead()) {
        state.set(State.READY);
        LOG.infof(
            "deployment routing projection caught up to qits-events (%d frame(s) handled)",
            result.handled());
        return;
      }
      LOG.warnf(
          "deployment routing projection is not ready (%s); retrying in %s",
          result.status(), retry);
      if (!waitForRetry()) {
        return;
      }
      result = catchup.catchUp(DeploymentActiveSubscriber.CONSUMER_ID);
    }
  }

  private boolean waitForRetry() {
    try {
      Thread.sleep(retry);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      LOG.warn("deployment routing projection catch-up was interrupted; leaving readiness down");
      return false;
    }
  }
}
