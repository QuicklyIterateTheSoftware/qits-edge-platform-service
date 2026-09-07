package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.inject.Vetoed;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The projection that decides which project slugs the edge's certificate carries, driven through
 * hand-built frames exactly as qits-projects publishes them.
 *
 * <p><b>It carries {@link StubGateways} although it proxies nothing</b>, and that is not leftover.
 * A {@code @QuarkusTest} whose configuration differs from the class before it RESTARTS Quarkus, and
 * a WebSocket upgrade through {@code vertx-http-proxy} only survives the first start in a JVM — so
 * a second configuration in this suite is how {@code EdgeRoutingTest}'s socket test starts failing
 * for no visible reason. One resource, one configuration, one start; see that class's javadoc.
 *
 * <p><b>No ACME order is ever placed here.</b> The subscriber is built by hand against a recording
 * stand-in for {@link EdgeCertificateManager}, so "a create asks for a reconcile" is asserted as a
 * call rather than as a certificate. The real manager is inert in this configuration anyway ({@code
 * qits.edge.acme.enabled=false}), which is precisely why it could not be asserted on.
 */
@QuarkusTest
@WithTestResource(StubGateways.class)
class ProjectSansTest {

  @Inject EdgeProjects projects;

  @Inject
  @DataSource("edge")
  AgroalDataSource edgeDataSource;

  /**
   * The reconcile trigger, counted instead of performed.
   *
   * <p>{@code @Vetoed} because {@code @ApplicationScoped} is {@code @Inherited}: without it this
   * subclass is discovered as a SECOND bean of the manager's type and every injection point in the
   * application becomes ambiguous.
   */
  @Vetoed
  static final class RecordingCertificates extends EdgeCertificateManager {

    final AtomicInteger requests = new AtomicInteger();

    RecordingCertificates() {
      super(null, null, null, null);
    }

    @Override
    public void requestReconcile() {
      requests.incrementAndGet();
    }
  }

  private RecordingCertificates certificates;
  private ProjectLifecycleSubscriber subscriber;

  @BeforeEach
  void freshProjection() throws SQLException {
    try (Connection connection = edgeDataSource.getConnection();
        PreparedStatement delete = connection.prepareStatement("delete from edge_project")) {
      delete.executeUpdate();
    }
    projects.load(null);
    certificates = new RecordingCertificates();
    subscriber = new ProjectLifecycleSubscriber(projects, certificates);
  }

  @Test
  void theConsumerReplaysFromTheEpochUnderItsOwnName() {
    // A new edge must learn every project ever announced: a watermark starting at today's head is a
    // certificate covering only the projects created after the boot.
    assertEquals("edge-project-sans", subscriber.consumerId());
    assertTrue(subscriber.replayFromEpoch());
    assertEquals(java.util.Set.of("ProjectCreated", "ProjectDeleted"), subscriber.signatures());
  }

  @Test
  void aCreatedProjectBecomesASlugAndAsksForOneReconcile() {
    subscriber.onFrame(created("acme", "p-1", Instant.parse("2026-09-07T10:00:00Z")));

    assertTrue(projects.slugs().contains("acme"));
    assertEquals(1, certificates.requests.get());
  }

  @Test
  void aDeletedProjectLeavesTheSlugSetAndOrdersNothing() {
    // A shrinking desired set is not a missing SAN: every name on the installed certificate still
    // answers, so the wildcard ages out at the next renewal rather than spending one of Let's
    // Encrypt's five duplicate certificates a week.
    subscriber.onFrame(created("acme", "p-1", Instant.parse("2026-09-07T10:00:00Z")));
    certificates.requests.set(0);

    subscriber.onFrame(deleted("acme", "p-1", Instant.parse("2026-09-07T11:00:00Z")));

    assertFalse(projects.slugs().contains("acme"));
    assertEquals(0, certificates.requests.get());
  }

  @Test
  void aHistoricalCreateDeliveredAfterTheDeleteDoesNotResurrectTheProject() {
    // The order catch-up actually delivers in: this consumer replays from the epoch, so a live
    // delete is handled before the create it followed. Last-writer-wins by occurredAt is the
    // answer, and the tombstone row is what makes it possible.
    subscriber.onFrame(deleted("acme", "p-1", Instant.parse("2026-09-07T11:00:00Z")));
    subscriber.onFrame(created("acme", "p-1", Instant.parse("2026-09-07T10:00:00Z")));

    assertFalse(projects.slugs().contains("acme"));
    assertEquals(0, certificates.requests.get());
  }

  @Test
  void replayingTheSameContentChangesNothingAndOrdersNothing() {
    EventFrame create = created("acme", "p-1", Instant.parse("2026-09-07T10:00:00Z"));
    subscriber.onFrame(create);
    certificates.requests.set(0);

    // The identical frame, as a catch-up hands it over again — and the same announcement carrying a
    // fresh event id, which is what a republish looks like. Neither moves the served set.
    subscriber.onFrame(create);
    subscriber.onFrame(created("acme", "p-1", Instant.parse("2026-09-07T12:00:00Z")));

    assertEquals(java.util.Set.of("acme"), projects.slugs());
    assertEquals(0, certificates.requests.get());
  }

  @Test
  void aPoisonPayloadIsSettledRatherThanThrown() {
    // Each of these is permanently bad, so a throw would pin this consumer's watermark forever and
    // the certificate would never learn about any project published after it. And a slug that is
    // not a DNS label must never reach CertificateNames, where one bad name refuses the order for
    // every good project too.
    subscriber.onFrame(
        frame(ProjectLifecycleSubscriber.CREATED, new JsonObject().put("projectId", "p-1")));
    subscriber.onFrame(
        frame(
            ProjectLifecycleSubscriber.CREATED,
            new JsonObject().put("projectId", "p-2").put("slug", "acme.evil")));
    subscriber.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(),
            ProjectLifecycleSubscriber.CREATED,
            Instant.now(),
            "{not json",
            null,
            null,
            null));

    assertTrue(projects.slugs().isEmpty());
    assertEquals(0, certificates.requests.get());
  }

  @Test
  void anInterruptedCatchUpLowersTheRoutingBarrierRatherThanLeavingItUp() throws Exception {
    // The slug set is a ROUTING input, so this bootstrap holds a 503 over the names whose reading
    // needs it. Returning from the retry loop with that barrier still up would leave a live edge
    // answering "retry shortly" to those names for as long as it runs, with nothing left running
    // to lower it — the one state the barrier must never reach. What it falls back to is the
    // behaviour the platform had before the barrier existed.
    ProjectSansBootstrap bootstrap =
        new ProjectSansBootstrap(
            alwaysUnavailable(), certificates, true, java.time.Duration.ofSeconds(30));
    Thread worker = new Thread(bootstrap::catchUpAndRequestReconcile, "an-interrupted-catch-up");
    worker.start();
    awaitSleeping(worker);
    assertFalse(
        bootstrap.authoritative(), "a projection that has read nothing is not authoritative");

    worker.interrupt();
    worker.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));

    assertFalse(worker.isAlive());
    assertTrue(bootstrap.authoritative());
    // And nothing is ordered on the way out: the desired set was never complete.
    assertEquals(0, certificates.requests.get());
  }

  /** A qits-events nobody can read, so the loop always reaches its retry. */
  private static DeploymentProjectionCatchup alwaysUnavailable() {
    return new DeploymentProjectionCatchup() {
      @Override
      public eu.wohlben.qits.eventstream.control.CatchupResult rebuildFromEpoch(String consumerId) {
        return unavailable(consumerId);
      }

      @Override
      public eu.wohlben.qits.eventstream.control.CatchupResult catchUp(String consumerId) {
        return unavailable(consumerId);
      }

      private eu.wohlben.qits.eventstream.control.CatchupResult unavailable(String consumerId) {
        return new eu.wohlben.qits.eventstream.control.CatchupResult(
            consumerId, eu.wohlben.qits.eventstream.control.CatchupResult.Status.UNAVAILABLE, 0);
      }
    };
  }

  /** Until the thread is inside its retry sleep, so the interrupt lands where the code reads it. */
  private static void awaitSleeping(Thread worker) throws InterruptedException {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (worker.getState() == Thread.State.TIMED_WAITING) {
        return;
      }
      Thread.sleep(5);
    }
    throw new AssertionError("the catch-up never reached its retry wait");
  }

  /**
   * The wire shape qits-projects publishes, unknown fields and all.
   *
   * <p>{@code projectName} and {@code createdAt}/{@code deletedAt} are on the wire and are on no
   * record component here, which is the tolerance this fixture pins: the publisher adds a field and
   * the edge keeps reading its frames without waiting for a Maven release. The display name is
   * spelled {@code projectName} rather than {@code name} on that side, and the edge's indifference
   * to which is the point — it reads neither.
   */
  private static EventFrame created(String slug, String projectId, Instant at) {
    return frame(
        ProjectLifecycleSubscriber.CREATED,
        at,
        new JsonObject()
            .put("projectId", projectId)
            .put("slug", slug)
            .put("projectName", "The " + slug + " project")
            .put("createdAt", at.toString()));
  }

  /** The same, minus the display name: an optional field's absence is not an unreadable frame. */
  private static EventFrame deleted(String slug, String projectId, Instant at) {
    return frame(
        ProjectLifecycleSubscriber.DELETED,
        at,
        new JsonObject()
            .put("projectId", projectId)
            .put("slug", slug)
            .put("deletedAt", at.toString()));
  }

  private static EventFrame frame(String name, JsonObject payload) {
    return frame(name, Instant.now(), payload);
  }

  private static EventFrame frame(String name, Instant at, JsonObject payload) {
    return new EventFrame(
        UUID.randomUUID().toString(), name, at, payload.encode(), null, null, null);
  }
}
