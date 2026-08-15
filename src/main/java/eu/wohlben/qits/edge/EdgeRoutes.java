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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The edge's persisted projection of successful deployments.
 *
 * <p>The database is the recoverable source: it is reconstructed from qits-events after a loss. The
 * immutable in-memory index is the serving copy, so selecting an upstream never blocks the Vert.x
 * event loop on PostgreSQL.
 */
@ApplicationScoped
public class EdgeRoutes {

  @Inject
  @DataSource("edge")
  AgroalDataSource dataSource;

  private volatile Map<String, List<EdgeEndpoint>> byEnvironment = Map.of();

  void load(@Observes StartupEvent ignored) {
    byEnvironment = readAll();
  }

  /** The longest matching active prefix in this environment, or null for gateway compatibility. */
  public EdgeEndpoint resolve(String environment, String path) {
    for (EdgeEndpoint endpoint : byEnvironment.getOrDefault(environment, List.of())) {
      if (endpoint.matches(path)) {
        return endpoint;
      }
    }
    return null;
  }

  /** Home plus the labelled primary routes, in their deployment-selected order. */
  public List<NavigationRoute.Link> navigation(String environment) {
    List<NavigationRoute.Link> links = new ArrayList<>();
    links.add(new NavigationRoute.Link(NavigationRoute.HOME_LABEL, "/"));
    byEnvironment.getOrDefault(environment, List.of()).stream()
        .filter(endpoint -> endpoint.navigationLabel() != null)
        .sorted(
            Comparator.comparing(EdgeEndpoint::navigationPosition)
                .thenComparing(EdgeEndpoint::navigationLabel)
                .thenComparing(EdgeEndpoint::path))
        .map(
            endpoint -> new NavigationRoute.Link(endpoint.navigationLabel(), href(endpoint.path())))
        .forEach(links::add);
    return List.copyOf(links);
  }

  /**
   * Replaces one application's complete route snapshot, including an explicitly empty one. Events
   * delivered late cannot roll a newer active deployment back: frame time, then event id, is the
   * tie-safe order used for that application's projection.
   *
   * @return true only when this event became the active snapshot
   */
  // qits-eventstream owns its claim ledger in a different datasource. Suspending that transaction
  // avoids pretending two ordinary PostgreSQL pools are one XA transaction: this snapshot commits
  // wholly or not at all, and an outer claim failure merely replays the same idempotent event.
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public boolean replace(
      String environment,
      String application,
      String eventId,
      Instant occurredAt,
      List<EdgeEndpoint> endpoints) {
    if (eventId == null || eventId.isBlank() || occurredAt == null) {
      throw new IllegalArgumentException("A DeploymentActive frame needs an id and occurredAt.");
    }
    validateSnapshot(environment, application, endpoints);
    try (Connection connection = dataSource.getConnection()) {
      if (!newer(connection, environment, application, eventId, occurredAt)) {
        return false;
      }
      rejectConflicts(connection, environment, application, endpoints);
      try (PreparedStatement delete =
          connection.prepareStatement(
              "delete from edge_deployment_snapshot where environment_name = ? and application_name = ?")) {
        delete.setString(1, environment);
        delete.setString(2, application);
        delete.executeUpdate();
      }
      try (PreparedStatement insert =
          connection.prepareStatement(
              "insert into edge_deployment_snapshot (environment_name, application_name, event_id, occurred_at) values (?, ?, ?, ?)")) {
        insert.setString(1, environment);
        insert.setString(2, application);
        insert.setString(3, eventId);
        insert.setTimestamp(4, java.sql.Timestamp.from(occurredAt));
        insert.executeUpdate();
      }
      for (EdgeEndpoint endpoint : endpoints) {
        try (PreparedStatement insert =
            connection.prepareStatement(
                "insert into edge_endpoint (environment_name, application_name, path, upstream_host, upstream_port, navigation_label, navigation_position) values (?, ?, ?, ?, ?, ?, ?)")) {
          insert.setString(1, environment);
          insert.setString(2, application);
          insert.setString(3, endpoint.path());
          insert.setString(4, endpoint.upstream().host());
          insert.setInt(5, endpoint.upstream().port());
          insert.setString(6, endpoint.navigationLabel());
          if (endpoint.navigationPosition() == null) {
            insert.setObject(7, null);
          } else {
            insert.setInt(7, endpoint.navigationPosition());
          }
          insert.executeUpdate();
        }
      }
      // This is after every statement that can fail. The durable event listener only sees success
      // once this method returns; a later restart loads PostgreSQL again, never this cache.
      byEnvironment = readAll(connection);
      return true;
    } catch (SQLException failure) {
      throw new IllegalStateException("could not replace the edge routing projection", failure);
    }
  }

  private static void validateSnapshot(
      String environment, String application, List<EdgeEndpoint> endpoints) {
    if (environment == null
        || environment.isBlank()
        || application == null
        || application.isBlank()) {
      throw new IllegalArgumentException(
          "A deployment route snapshot needs environment and application.");
    }
    if (endpoints == null) {
      throw new IllegalArgumentException("A deployment route snapshot did not carry endpoints.");
    }
    boolean navigationSeen = false;
    for (EdgeEndpoint endpoint : endpoints) {
      if (!environment.equals(endpoint.environment())
          || !application.equals(endpoint.application())) {
        throw new IllegalArgumentException("An endpoint is not owned by its deployment snapshot.");
      }
      if (endpoint.navigationLabel() != null && navigationSeen) {
        throw new IllegalArgumentException("One application may publish one navigation route.");
      }
      navigationSeen |= endpoint.navigationLabel() != null;
    }
  }

  private static boolean newer(
      Connection connection,
      String environment,
      String application,
      String eventId,
      Instant occurredAt)
      throws SQLException {
    try (PreparedStatement read =
        connection.prepareStatement(
            "select occurred_at, event_id from edge_deployment_snapshot where environment_name = ? and application_name = ? for update")) {
      read.setString(1, environment);
      read.setString(2, application);
      try (ResultSet result = read.executeQuery()) {
        if (!result.next()) {
          return true;
        }
        Instant activeAt = result.getTimestamp(1).toInstant();
        int order = occurredAt.compareTo(activeAt);
        return order > 0 || (order == 0 && eventId.compareTo(result.getString(2)) > 0);
      }
    }
  }

  private static void rejectConflicts(
      Connection connection, String environment, String application, List<EdgeEndpoint> endpoints)
      throws SQLException {
    for (EdgeEndpoint endpoint : endpoints) {
      try (PreparedStatement existing =
          connection.prepareStatement(
              "select application_name from edge_endpoint where environment_name = ? and path = ? and application_name <> ?")) {
        existing.setString(1, environment);
        existing.setString(2, endpoint.path());
        existing.setString(3, application);
        try (ResultSet result = existing.executeQuery()) {
          if (result.next()) {
            throw new IllegalArgumentException(
                "The active "
                    + environment
                    + " route "
                    + endpoint.path()
                    + " already belongs to "
                    + result.getString(1)
                    + ".");
          }
        }
      }
    }
  }

  private Map<String, List<EdgeEndpoint>> readAll() {
    try (Connection connection = dataSource.getConnection()) {
      return readAll(connection);
    } catch (SQLException failure) {
      throw new IllegalStateException("could not load the edge routing projection", failure);
    }
  }

  private static Map<String, List<EdgeEndpoint>> readAll(Connection connection)
      throws SQLException {
    Map<String, List<EdgeEndpoint>> routes = new LinkedHashMap<>();
    try (PreparedStatement query =
            connection.prepareStatement(
                "select environment_name, application_name, path, upstream_host, upstream_port, navigation_label, navigation_position from edge_endpoint order by environment_name, length(path) desc, path");
        ResultSet result = query.executeQuery()) {
      while (result.next()) {
        EdgeEndpoint endpoint =
            new EdgeEndpoint(
                result.getString(1),
                result.getString(2),
                result.getString(3),
                new Upstream(result.getString(4), result.getInt(5)),
                result.getString(6),
                (Integer) result.getObject(7));
        routes.computeIfAbsent(endpoint.environment(), ignored -> new ArrayList<>()).add(endpoint);
      }
    }
    Map<String, List<EdgeEndpoint>> immutable = new LinkedHashMap<>();
    routes.forEach((environment, endpoints) -> immutable.put(environment, List.copyOf(endpoints)));
    return Map.copyOf(immutable);
  }

  private static String href(String path) {
    return path.equals("/") ? path : path + "/";
  }
}
