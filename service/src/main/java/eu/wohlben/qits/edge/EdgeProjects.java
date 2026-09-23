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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The edge's persisted projection of which projects exist, by slug, and what each one supports.
 *
 * <p><b>It began as a certificate input and is now a ROUTING one too.</b> A project's slug is a
 * label in two of the SAN tiers the edge orders — {@code *.<slug>.<domain>} and {@code
 * *.<env>.<slug>.<domain>}, see {@link eu.wohlben.qits.edge.acme.CertificateNames} — so the edge
 * has to know the set of slugs to know the set of names, and {@code supportsEnvironments} to know
 * which of them cost the second tier. Since the grammar grew its project tiers it is also read per
 * request: {@code <slug>.<env>.<domain>} is a project's door and {@code
 * <app>.<slug>.<env>.<domain>} is one application for one project, and only this says which middle
 * labels are slugs at all. Two consequences follow, and both are load-bearing — {@link #slugs()} is
 * on the request path and must never touch PostgreSQL, and a projection that has not caught up
 * routes those names wrongly, which is what {@link ProjectSansBootstrap#authoritative()} and the
 * router's 503 exist for.
 *
 * <p>The database is the recoverable source, reconstructed from qits-events after a loss. The
 * volatile in-memory copy is what {@link #slugs()} serves, so building a certificate name set never
 * queries PostgreSQL. Deliberately the same shape as {@link EdgeRoutes}, down to the last-writer-
 * wins guard, because it is the same problem: a durable consumer replaying from the epoch delivers
 * historical frames late relative to live ones.
 *
 * <p><b>It carries more than presence now.</b> {@code supportsEnvironments} says whether a project
 * has a tier of environments under it at all, which is a property of the project and not of any one
 * name, so this is where it belongs. Its first reader is the certificate: {@link #projects()} feeds
 * {@code CertificateNames.capped}, where an env-less project costs one SAN and one that supports
 * environments costs one per environment as well. It was projected ahead of that reader on purpose,
 * so the flag arrived on a projection that had already replayed the log rather than one that starts
 * learning it on the day the behaviour changes. The ROUTING readers are still to come.
 */
@ApplicationScoped
public class EdgeProjects {

  @Inject
  @DataSource("edge")
  AgroalDataSource dataSource;

  /**
   * The two views of one read, swapped together.
   *
   * <p>One volatile field rather than two, because {@link #slugs()} and {@link #projects()} must
   * never disagree: two fields assigned in sequence let a reader take the new map and the old slug
   * set, which is a certificate order and a routing decision made against different snapshots of
   * the same instant. {@code slugs} is the map's own key set, so the pair costs one map.
   *
   * @param bySlug present projects only, slug to whether that project has environments
   * @param slugs the same keys, the same order, as the set the request path and the ACME order read
   */
  private record Projection(Map<String, Boolean> bySlug, Set<String> slugs) {}

  private volatile Projection projection = empty();

  void load(@Observes StartupEvent ignored) {
    projection = readAll();
  }

  /**
   * The slugs of the projects that exist, sorted.
   *
   * <p>Sorted, and an ORDERED set, because the caller builds certificate names out of it: {@code
   * Set.copyOf} — and {@code Map.copyOf}, which the map behind this set must avoid for exactly the
   * same reason — would iterate in an order that changes with every JVM start, and a SAN list whose
   * names merely permuted is a certificate that looks different to nobody and gets reordered on
   * every restart. This is a view of the loaded map's key set, held rather than derived per call:
   * it is on the request path, and it allocates nothing.
   */
  public Set<String> slugs() {
    return projection.slugs();
  }

  /**
   * The projects that exist, slug to whether that project has environments, in {@link #slugs()}
   * order.
   *
   * <p>Exactly the rows {@link #slugs()} serves — present projects only, tombstones excluded — so
   * the two can be read side by side without one of them naming a project the other does not. Like
   * {@code slugs()} it is served from memory and never touches PostgreSQL.
   */
  public Map<String, Boolean> projects() {
    return projection.bySlug();
  }

  /**
   * Whether this project has a tier of environments under it.
   *
   * <p>True for a slug this projection has never heard of, which is the same compatibility rule the
   * column's default and the wire DTO's normalisation carry: absence means "has environments"
   * everywhere, so a caller that asks before the projection has caught up gets the answer every
   * project on the platform had before the flag existed, rather than a false claim that the project
   * has none.
   */
  public boolean supportsEnvironments(String slug) {
    return projection.bySlug().getOrDefault(slug, Boolean.TRUE);
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
   * requesting, and a create re-announced with a new event id is not. <b>It stays the SLUG SET's
   * answer now that a second field rides along</b> — a frame that only flips {@code
   * supportsEnvironments} writes the new value and returns false, because the desired SAN list is
   * identical and ordering for it would spend one of Let's Encrypt's five duplicate certificates a
   * week to install the names that are already installed.
   *
   * <p><b>A delete carries no flag, and writes TRUE.</b> {@code ProjectDeleted} has no such field
   * to carry, so the caller passes the neutral value rather than a guess: the alternative, reading
   * the row back and carrying the old flag through, would make the tombstone's meaning depend on
   * the order the frames arrived in, which is precisely what this table is written to be free of.
   * Nothing is misled by it — a tombstone appears in neither {@link #slugs()} nor {@link
   * #projects()}, so the value is visible only to an operator reading the table, where "true" is
   * the same "no publisher said otherwise" it means everywhere else. A later create for the same
   * slug overwrites it with whatever that frame carries.
   *
   * @return true only when this frame moved the served set — a slug that appeared or disappeared
   */
  // qits-eventstream owns its claim ledger in a different datasource. Suspending that transaction
  // avoids pretending two ordinary PostgreSQL pools are one XA transaction: this row commits wholly
  // or not at all, and an outer claim failure merely replays the same idempotent frame.
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public boolean apply(
      String slug,
      String projectId,
      boolean present,
      boolean supportsEnvironments,
      String eventId,
      Instant occurredAt) {
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
              insert into edge_project
                (slug, project_id, present, supports_environments, event_id, occurred_at)
              values (?, ?, ?, ?, ?, ?)
              on conflict (slug) do update
                set project_id = excluded.project_id,
                    present = excluded.present,
                    supports_environments = excluded.supports_environments,
                    event_id = excluded.event_id,
                    occurred_at = excluded.occurred_at
              """)) {
        upsert.setString(1, slug);
        upsert.setString(2, projectId == null ? "" : projectId);
        upsert.setBoolean(3, present);
        upsert.setBoolean(4, supportsEnvironments);
        upsert.setString(5, eventId);
        upsert.setTimestamp(6, java.sql.Timestamp.from(occurredAt));
        upsert.executeUpdate();
      }
      // After every statement that can fail. The durable event listener only sees success once this
      // method returns; a later restart loads PostgreSQL again, never this cache.
      projection = readAll(connection);
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

  private Projection readAll() {
    try (Connection connection = dataSource.getConnection()) {
      return readAll(connection);
    } catch (SQLException failure) {
      throw new IllegalStateException("could not load the edge project projection", failure);
    }
  }

  /**
   * The present rows, in slug order, as the one pair {@link #projection} is swapped to.
   *
   * <p>{@code LinkedHashMap} filled by an {@code order by slug} query, never {@code Map.copyOf}:
   * the copy factories salt their iteration order per JVM, and this map's key set IS the SAN order
   * — see {@link #slugs()}.
   */
  private static Projection readAll(Connection connection) throws SQLException {
    LinkedHashMap<String, Boolean> present = new LinkedHashMap<>();
    try (PreparedStatement query =
            connection.prepareStatement(
                "select slug, supports_environments from edge_project where present order by slug");
        ResultSet result = query.executeQuery()) {
      while (result.next()) {
        present.put(result.getString(1), result.getBoolean(2));
      }
    }
    return projectionOf(present);
  }

  private static Projection empty() {
    return projectionOf(new LinkedHashMap<>());
  }

  /**
   * Wraps one ordered map as both views. The key set is taken from the UNMODIFIABLE map rather than
   * from the {@code LinkedHashMap}, so the set a caller holds cannot remove a slug from underneath
   * the projection.
   */
  private static Projection projectionOf(LinkedHashMap<String, Boolean> ordered) {
    Map<String, Boolean> bySlug = Collections.unmodifiableMap(ordered);
    return new Projection(bySlug, bySlug.keySet());
  }
}
