package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.enterprise.inject.Vetoed;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The window between "a project appeared" and "order a certificate".
 *
 * <p>Plain JUnit against a hand-built configuration, with the ACME ORDER — and only the order —
 * replaced by a counter: what is being pinned is the SCHEDULING, and it is worth pinning because
 * every failure it prevents is expensive and invisible in a green build. A request that ran inline
 * would place an order, DNS propagation waits and all, on the event funnel's delivery thread; a
 * request per frame would place one order per project during epoch replay, of which the second
 * already draws on Let's Encrypt's production limit of five duplicate certificates a week; and a
 * request dropped because another run held the guard is the first certificate of a fresh edge
 * coming back with no project on it.
 */
class EdgeCertificateDebounceTest {

  private static final Duration DEBOUNCE = Duration.ofSeconds(1);

  /**
   * The manager with its ORDER removed and nothing else.
   *
   * <p><b>{@code reconcileSafely} is the real one</b>, which it did not use to be: this double
   * overrode that method, so every test here drove past the running-guard the class javadoc claims
   * they pin and the one bug they were meant to catch — a request silently lost to a run already in
   * flight — was invisible from inside them. What is replaced is {@link
   * EdgeCertificateManager#reconcile()}, the ACME order itself, so the guard, the debounce and the
   * re-arm are all exercised as they ship.
   *
   * <p>{@code @Vetoed} because {@code @ApplicationScoped} is {@code @Inherited}: without it this
   * subclass is discovered as a SECOND bean of the manager's type and every injection point in the
   * application becomes ambiguous.
   */
  @Vetoed
  static final class CountingManager extends EdgeCertificateManager {

    final AtomicInteger reconciles = new AtomicInteger();

    /** Held down by a test that wants an order still in flight; counted down to complete it. */
    final CountDownLatch order = new CountDownLatch(1);

    volatile boolean holdTheOrderOpen;

    CountingManager(AcmeConfig acme) {
      super(acme, null, null, null);
    }

    @Override
    void reconcile() throws Exception {
      reconciles.incrementAndGet();
      if (holdTheOrderOpen) {
        order.await();
      }
    }
  }

  /**
   * An edge that has ACME to do, which is what makes {@code reconcileSafely} reach the guard: with
   * the mode off it returns before taking it, and every test here would pass without one.
   */
  private static AcmeConfig acme() {
    SmallRyeConfig config =
        new SmallRyeConfigBuilder()
            .withMapping(AcmeConfig.class)
            .withSources(
                new EnvConfigSource(
                    Map.of(
                        "QITS_EDGE_ACME_RECONCILE_DEBOUNCE", DEBOUNCE.toString(),
                        "QITS_EDGE_ACME_ENABLED", "true",
                        "QITS_EDGE_ACME_MODE", "staging",
                        "QITS_EDGE_ACME_DOMAIN", "wohlben.eu"),
                    300))
            // The "30d" defaults are Quarkus' Duration spelling, not the ISO one a bare SmallRye
            // knows; the runtime converter is what reads them in a deployment too.
            .withConverter(Duration.class, 200, new DurationConverter())
            .build();
    return config.getConfigMapping(AcmeConfig.class);
  }

  @Test
  void theDefaultWindowIsThirtySeconds() {
    // The key is optional and the default is the contract: an edge that never sets it still
    // collapses an epoch replay's whole burst of ProjectCreated frames into one order.
    SmallRyeConfig config =
        new SmallRyeConfigBuilder()
            .withMapping(AcmeConfig.class)
            .withSources(new EnvConfigSource(Map.of(), 300))
            .withConverter(Duration.class, 200, new DurationConverter())
            .build();

    assertEquals(
        Duration.ofSeconds(30), config.getConfigMapping(AcmeConfig.class).reconcileDebounce());
  }

  @Test
  void aBurstOfRequestsIsOneReconcileAndNoneOfItRunsInline() throws Exception {
    CountingManager manager = new CountingManager(acme());

    manager.requestReconcile();
    manager.requestReconcile();
    manager.requestReconcile();

    // The funnel's thread returned without doing any of the work; that is the inline guarantee.
    assertEquals(0, manager.reconciles.get());
    awaitReconciles(manager, 1);
    Thread.sleep(DEBOUNCE.dividedBy(4));
    assertEquals(1, manager.reconciles.get(), "the three requests must share one order");
  }

  @Test
  void aRequestAfterTheWindowOpensAnotherOne() throws Exception {
    // A project announced after an order has run must not ride the window that already closed: the
    // pending flag is cleared before the work, so the next request schedules a fresh one.
    CountingManager manager = new CountingManager(acme());

    manager.requestReconcile();
    awaitReconciles(manager, 1);
    manager.requestReconcile();

    awaitReconciles(manager, 2);
  }

  @Test
  void aRequestThatLosesToARunAlreadyInFlightRunsOnceThatRunIsDone() throws Exception {
    // THE FRESH-BOOT SHAPE, and the bug it is written for. The StartupEvent's own reconcile holds
    // the guard for as long as an ACME order takes — up to the 10m DNS timeout — and
    // ProjectSansBootstrap's request lands inside that window. It used to be dropped there, so the
    // first certificate a new edge installed carried no project SANs at all until the 12h sweep,
    // which is the whole thing that bootstrap exists to prevent.
    CountingManager manager = new CountingManager(acme());
    manager.holdTheOrderOpen = true;
    Thread inFlight = new Thread(manager::reconcileSafely, "an-order-in-flight");
    inFlight.start();
    awaitReconciles(manager, 1);

    manager.requestReconcile();
    // Two windows, so the request has certainly fired and certainly lost: the guard is held.
    Thread.sleep(DEBOUNCE.multipliedBy(2));
    assertEquals(1, manager.reconciles.get(), "the guard is held, so nothing else may run");

    manager.order.countDown();
    inFlight.join();

    // And it was re-armed rather than lost, so it runs on its own once the order completes.
    awaitReconciles(manager, 2);
  }

  private static void awaitReconciles(CountingManager manager, int expected) throws Exception {
    Duration limit = DEBOUNCE.multipliedBy(10);
    long deadline = System.nanoTime() + limit.toNanos();
    while (System.nanoTime() < deadline) {
      if (manager.reconciles.get() >= expected) {
        assertEquals(expected, manager.reconciles.get());
        return;
      }
      Thread.sleep(10);
    }
    fail("expected " + expected + " reconcile(s) within " + limit + ", saw " + manager.reconciles);
  }
}
