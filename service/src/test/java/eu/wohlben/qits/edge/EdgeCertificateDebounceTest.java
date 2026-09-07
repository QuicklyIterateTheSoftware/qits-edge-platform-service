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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The window between "a project appeared" and "order a certificate".
 *
 * <p>Plain JUnit against a hand-built configuration, with the work itself replaced by a counter:
 * what is being pinned is the SCHEDULING, and it is worth pinning because both failures it prevents
 * are expensive and invisible in a green build. A request that ran inline would place an ACME order
 * — DNS propagation waits and all — on the event funnel's delivery thread, and a request per frame
 * would place one order per project during epoch replay, of which the second already draws on Let's
 * Encrypt's production limit of five duplicate certificates a week.
 */
class EdgeCertificateDebounceTest {

  private static final Duration DEBOUNCE = Duration.ofSeconds(1);

  /**
   * The manager with its work removed.
   *
   * <p>{@code @Vetoed} because {@code @ApplicationScoped} is {@code @Inherited}: without it this
   * subclass is discovered as a SECOND bean of the manager's type and every injection point in the
   * application becomes ambiguous.
   */
  @Vetoed
  static final class CountingManager extends EdgeCertificateManager {

    final AtomicInteger reconciles = new AtomicInteger();

    CountingManager(AcmeConfig acme) {
      super(acme, null, null, null);
    }

    @Override
    void reconcileSafely() {
      reconciles.incrementAndGet();
    }
  }

  private static AcmeConfig acme() {
    SmallRyeConfig config =
        new SmallRyeConfigBuilder()
            .withMapping(AcmeConfig.class)
            .withSources(
                new EnvConfigSource(
                    Map.of("QITS_EDGE_ACME_RECONCILE_DEBOUNCE", DEBOUNCE.toString()), 300))
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
    // A project announced while an order is in flight must not be swallowed by it: the pending flag
    // is cleared before the work, so the next request schedules a fresh window.
    CountingManager manager = new CountingManager(acme());

    manager.requestReconcile();
    awaitReconciles(manager, 1);
    manager.requestReconcile();

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
