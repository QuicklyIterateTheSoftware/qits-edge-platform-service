package eu.wohlben.qits.edge;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The edge's persisted projection of which projects exist, by slug.
 *
 * <p>It exists for the certificate and for nothing else. A project's slug is a label in two of the
 * SAN tiers the edge orders — {@code *.<slug>.<domain>} and {@code *.<slug>.<env>.<domain>}, see
 * {@link eu.wohlben.qits.edge.acme.CertificateNames} — so the edge has to know the set of slugs to
 * know the set of names. No request reads this projection: it decides what is ordered, never where
 * anything is routed.
 *
 * <p>The database is the recoverable source, reconstructed from qits-events after a loss. The
 * volatile in-memory copy is what {@link #slugs()} serves, so building a certificate name set never
 * queries PostgreSQL. Deliberately the same shape as {@link EdgeRoutes}, down to the last-writer-
 * wins guard, because it is the same problem: a durable consumer replaying from the epoch delivers
 * historical frames late relative to live ones.
 */
@ApplicationScoped
public class EdgeProjects {

  @Inject
  @DataSource("edge")
  AgroalDataSource dataSource;

  private volatile Set<String> slugs = Set.of();

  void load(@Observes StartupEvent ignored) {
    slugs = readAll();
  }

  /**
   * The slugs of the projects that exist, sorted.
   *
   * <p>Sorted, and an ORDERED set, because the caller builds certificate names out of it: {@code
   * Set.copyOf} would iterate in an order that changes with every JVM start, and a SAN list whose
   * names merely permuted is a certificate that looks different to nobody and gets reordered on
   * every restart.
   */
  public Set<String> slugs() {
    return slugs;
  }

  /**
   * Records one project lifecycle frame, keeping the newest one per slug.
   *
   * <p>{@code present} is the whole state: true for a create, false for a tombstone. A delete
   * leaves the row behind on purpose — see {@code V7__project_sans.sql} — so a historical create
   * replayed after it cannot resurrect a project that is gone.
   *
   * <p>Order is {@code occurred_at} then {@code event_id}, the tie-safe pair {@link EdgeRoutes}
   * uses. An older frame changes nothing at all; a newer one always advances the recorded position,
   * and the answer says whether it also changed what {@link #slugs()} serves. That distinction is
   * the point of the return value: it is what decides whether a certificate reconcile is worth
   * requesting, and a create re-announced with a new event id is not.
   *
   * @return true only when this frame moved the served set — a slug that appeared or disappeared
   */
  // qits-eventstream owns its claim ledger in a different datasource. Suspending that transaction
  // avoids pretending two ordinary PostgreSQL pools are one XA transaction: this row commits wholly
  // or not at all, and an outer claim failure merely replays the same idempotent frame.
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public boolean apply(
      String slug, String projectId, boolean present, String eventId, Instant occurredAt) {
    if (eventId == null || eventId.isBlank() || occurredAt == null) {
      throw new IllegalArgumentException("A project lifecycle frame needs an id and occurredAt.");
    }
    if (slug == null || !HostEnvironments.isLabel(slug)) {
      // The subscriber refuses this first and settles the frame; the rule is restated here because
      // a slug that is not a label would become a certificate name that fails the WHOLE order.
      throw new IllegalArgumentException(
          "`" + slug + "` cannot be a DNS label, so it cannot be a project slug.");
    }
    try (Connection connection = dataSource.getConnection()) {
      Boolean recorded = recordedPresence(connection, slug, eventId, occurredAt);
      if (recorded == null) {
        return false;
      }
      try (PreparedStatement upsert =
          connection.prepareStatement(
              """
              insert into edge_project (slug, project_id, present, event_id, occurred_at)
              values (?, ?, ?, ?, ?)
              on conflict (slug) do update
                set project_id = excluded.project_id,
                    present = excluded.present,
                    event_id = excluded.event_id,
                    occurred_at = excluded.occurred_at
              """)) {
        upsert.setString(1, slug);
        upsert.setString(2, projectId == null ? "" : projectId);
        upsert.setBoolean(3, present);
        upsert.setString(4, eventId);
        upsert.setTimestamp(5, java.sql.Timestamp.from(occurredAt));
        upsert.executeUpdate();
      }
      // After every statement that can fail. The durable event listener only sees success once this
      // method returns; a later restart loads PostgreSQL again, never this cache.
      slugs = readAll(connection);
      return recorded != present;
    } catch (SQLException failure) {
      throw new IllegalStateException("could not record the edge project projection", failure);
    }
  }

  /**
   * The presence this slug is currently recorded with, or null when this frame is not the newest.
   *
   * <p>{@code FALSE} for a slug with no row at all: a projection that has never heard of a project
   * serves it as absent, so a create really does change the served set and a delete arriving first
   * really does not.
   */
  private static Boolean recordedPresence(
      Connection connection, String slug, String eventId, Instant occurredAt) throws SQLException {
    try (PreparedStatement read =
        connection.prepareStatement(
            "select occurred_at, event_id, present from edge_project where slug = ? for update")) {
      read.setString(1, slug);
      try (ResultSet result = read.executeQuery()) {
        if (!result.next()) {
          return Boolean.FALSE;
        }
        Instant recordedAt = result.getTimestamp(1).toInstant();
        int order = occurredAt.compareTo(recordedAt);
        boolean newer = order > 0 || (order == 0 && eventId.compareTo(result.getString(2)) > 0);
        return newer ? result.getBoolean(3) : null;
      }
    }
  }

  private Set<String> readAll() {
    try (Connection connection = dataSource.getConnection()) {
      return readAll(connection);
    } catch (SQLException failure) {
      throw new IllegalStateException("could not load the edge project projection", failure);
    }
  }

  private static Set<String> readAll(Connection connection) throws SQLException {
    LinkedHashSet<String> present = new LinkedHashSet<>();
    try (PreparedStatement query =
            connection.prepareStatement(
                "select slug from edge_project where present order by slug");
        ResultSet result = query.executeQuery()) {
      while (result.next()) {
        present.add(result.getString(1));
      }
    }
    return Collections.unmodifiableSet(present);
  }
}
