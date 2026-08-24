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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The edge's persisted projection of successful deployments: where an environment's paths go, which
 * public name each service answers to, and where each service asks to be rendered in the platform's
 * navigation tree.
 *
 * <p>The database is the recoverable source: it is reconstructed from qits-events after a loss. The
 * immutable in-memory index is the serving copy, so selecting an upstream never blocks the Vert.x
 * event loop on PostgreSQL.
 */
@ApplicationScoped
public class EdgeRoutes {

  /**
   * The navigation vocabulary, closed and in the order {@code /main-navigation} renders it.
   *
   * <p>Closed because the shell instantiates a node per slot: a slot nothing knows about would be
   * published and never drawn, which is worse than a deployment being refused for a typo. The edge
   * knows nothing about projects or repositories — a slot says WHERE the shell hangs an entry, and
   * the shell decides what hangs there.
   */
  public static final List<String> SLOTS =
      List.of(
          "system",
          "platform",
          "project.detail",
          "services.details",
          "daemons.details",
          "libs.details",
          "frontends.details",
          "cli.details",
          "images.details");

  /** The legacy shape's only slot: an old frame's one navigation label means "global". */
  static final String SYSTEM_SLOT = "system";

  private static final int LABEL_LIMIT = 64;

  private static final int SUBPATH_LIMIT = 255;

  private static final int API_DOCS_LIMIT = 255;

  @Inject
  @DataSource("edge")
  AgroalDataSource dataSource;

  private volatile View view = View.empty();

  void load(@Observes StartupEvent ignored) {
    view = readAll();
  }

  /**
   * One navigation placement an application published: where in the tree it asks to appear, under
   * what name, how high — and, optionally, which view of the application it opens.
   *
   * <p>{@code subpath} is a client-side route segment the shell appends after the scope it
   * composes; null is every entry declared before the field existed, the application's root under
   * that scope. Relative on purpose — the edge validates the shape and passes it through, because
   * what it names is a view of the declaring application's SPA, not an edge route.
   */
  public record NavigationEntry(String slot, String label, int position, String subpath) {

    public NavigationEntry {
      slot = slot == null ? null : slot.strip();
      if (!SLOTS.contains(slot)) {
        throw new IllegalArgumentException(
            "`" + slot + "` is not a navigation slot. The vocabulary is " + SLOTS + ".");
      }
      if (label == null || label.isBlank() || label.strip().length() > LABEL_LIMIT) {
        throw new IllegalArgumentException(
            "A navigation label is blank or longer than " + LABEL_LIMIT + " characters.");
      }
      label = label.strip();
      if (position < 1) {
        throw new IllegalArgumentException("A navigation position starts at 1.");
      }
      subpath = subpath == null || subpath.isBlank() ? null : subpath.strip();
      if (subpath != null
          && (subpath.length() > SUBPATH_LIMIT
              || !subpath.matches("[a-z0-9]+(?:-[a-z0-9]+)*(?:/[a-z0-9]+(?:-[a-z0-9]+)*)*"))) {
        throw new IllegalArgumentException(
            "A navigation subpath is a relative lowercase path of at most "
                + SUBPATH_LIMIT
                + " characters, got `"
                + subpath
                + "`.");
      }
    }

    /** The placement every entry was before subpaths existed. */
    public NavigationEntry(String slot, String label, int position) {
      this(slot, label, position, null);
    }
  }

  /**
   * One application's whole published state: its routes in declaration order, the public name it
   * answers to, and its navigation placements.
   *
   * @param endpoints the routes, PRIMARY FIRST — the first one is the segment the application's own
   *     SPA is served under, and the upstream its host resolves to
   * @param browserHost the DNS label of {@code <host>.<env>.<domain>}, or null while it publishes
   *     none
   * @param apiDocsPath where the application's browsable API document lives, under one of its
   *     routes, or null for a service that documents no HTTP surface
   * @param navigation the placements, at most one per slot
   */
  public record Snapshot(
      List<EdgeEndpoint> endpoints,
      String browserHost,
      String apiDocsPath,
      List<NavigationEntry> navigation) {

    public Snapshot {
      endpoints = endpoints == null ? null : List.copyOf(endpoints);
      apiDocsPath = apiDocsPath == null || apiDocsPath.isBlank() ? null : apiDocsPath.strip();
      navigation = navigation == null ? List.of() : List.copyOf(navigation);
    }

    /** The pre-api-docs shape, which every already-written caller and test still means. */
    public Snapshot(
        List<EdgeEndpoint> endpoints, String browserHost, List<NavigationEntry> navigation) {
      this(endpoints, browserHost, null, navigation);
    }

    /** The routes alone, which is what an application that has not been flipped publishes. */
    public static Snapshot ofEndpoints(List<EdgeEndpoint> endpoints) {
      return new Snapshot(endpoints, null, null, List.of());
    }
  }

  /**
   * A public name and the service behind it.
   *
   * @param upstream the primary route's upstream, which is the process that serves the name itself
   * @param primaryPath that route's path — the segment the environment vhost still serves the same
   *     application under
   */
  public record ServiceHost(
      String application, String host, Upstream upstream, String primaryPath) {}

  /**
   * A placement as it is served: what an application published, plus the two facts the document
   * needs and the placement itself does not carry.
   *
   * @param host the application's public name, or null when it publishes none — a legacy frame
   * @param primaryPath its primary route, which is where a hostless entry still lives
   * @param subpath the view this entry opens, relative to the scope the shell composes, or null for
   *     the application's root
   */
  public record NavigationPlacement(
      String application,
      String slot,
      String label,
      int position,
      String host,
      String primaryPath,
      String subpath) {}

  /**
   * The serving copy, replaced whole. One object rather than four fields because a request reads
   * several of these maps and must never see two halves of different snapshots.
   */
  private record View(
      Map<String, List<EdgeEndpoint>> endpoints,
      Map<String, Map<String, ServiceHost>> hostsByName,
      Map<String, Map<String, ServiceHost>> hostsByApplication,
      Map<String, Map<String, String>> primaryPaths,
      Map<String, Map<String, String>> apiDocsPaths,
      Map<String, List<NavigationPlacement>> navigation) {

    static View empty() {
      return new View(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }
  }

  /** The longest matching active prefix in this environment, or null when no deployment owns it. */
  public EdgeEndpoint resolve(String environment, String path) {
    for (EdgeEndpoint endpoint : view.endpoints().getOrDefault(environment, List.of())) {
      if (endpoint.matches(path)) {
        return endpoint;
      }
    }
    return null;
  }

  /** The service this public name reaches in this environment, or null when nothing claims it. */
  public ServiceHost serviceHost(String environment, String host) {
    return host == null
        ? null
        : view.hostsByName().getOrDefault(environment, Map.of()).get(host.toLowerCase(Locale.ROOT));
  }

  /** This application's own public name in this environment, or null while it publishes none. */
  public ServiceHost applicationHost(String environment, String application) {
    return application == null
        ? null
        : view.hostsByApplication().getOrDefault(environment, Map.of()).get(application);
  }

  /**
   * This application's first-declared route in this environment, or null when it publishes none.
   *
   * <p>The primary route is the one an application is KNOWN by: the segment its SPA is served
   * under, and so the only one of its routes that means the same thing on somebody else's name.
   */
  public String primaryPath(String environment, String application) {
    return application == null
        ? null
        : view.primaryPaths().getOrDefault(environment, Map.of()).get(application);
  }

  /** Every placement in this environment, sorted by slot, then position, then label. */
  public List<NavigationPlacement> navigation(String environment) {
    return view.navigation().getOrDefault(environment, List.of());
  }

  /**
   * Every application's api-docs path in this environment, by application name. Only applications
   * that published one appear; the paths are what {@code /main-navigation} serves as the {@code
   * applications} object.
   */
  public Map<String, String> apiDocs(String environment) {
    return view.apiDocsPaths().getOrDefault(environment, Map.of());
  }

  /**
   * Where a browser typing the environment's own name belongs: qits-projects' host.
   *
   * <p>Read from the projection rather than configured — the {@code system} placement qits-projects
   * publishes, and the conventional name as the fallback. Null until one of them is known, and the
   * environment vhost then answers as it always did.
   */
  public ServiceHost projectsHost(String environment) {
    for (NavigationPlacement placement : navigation(environment)) {
      if (SYSTEM_SLOT.equals(placement.slot())
          && "qits-projects".equals(placement.application())
          && placement.host() != null) {
        return serviceHost(environment, placement.host());
      }
    }
    return serviceHost(environment, "projects");
  }

  /**
   * Replaces one application's complete snapshot, including an explicitly empty one. Events
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
      Snapshot snapshot) {
    if (eventId == null || eventId.isBlank() || occurredAt == null) {
      throw new IllegalArgumentException("A DeploymentActive frame needs an id and occurredAt.");
    }
    validateSnapshot(environment, application, snapshot);
    try (Connection connection = dataSource.getConnection()) {
      if (!newer(connection, environment, application, eventId, occurredAt)) {
        return false;
      }
      rejectPathConflicts(connection, environment, application, snapshot.endpoints());
      rejectHostConflict(connection, environment, application, snapshot.browserHost());
      try (PreparedStatement delete =
          connection.prepareStatement(
              "delete from edge_deployment_snapshot where environment_name = ? and application_name = ?")) {
        delete.setString(1, environment);
        delete.setString(2, application);
        delete.executeUpdate();
      }
      try (PreparedStatement insert =
          connection.prepareStatement(
              "insert into edge_deployment_snapshot (environment_name, application_name, event_id, occurred_at, browser_host, api_docs_path) values (?, ?, ?, ?, ?, ?)")) {
        insert.setString(1, environment);
        insert.setString(2, application);
        insert.setString(3, eventId);
        insert.setTimestamp(4, java.sql.Timestamp.from(occurredAt));
        insert.setString(5, snapshot.browserHost());
        insert.setString(6, snapshot.apiDocsPath());
        insert.executeUpdate();
      }
      int ordinal = 0;
      for (EdgeEndpoint endpoint : snapshot.endpoints()) {
        try (PreparedStatement insert =
            connection.prepareStatement(
                "insert into edge_endpoint (environment_name, application_name, path, upstream_host, upstream_port, ordinal) values (?, ?, ?, ?, ?, ?)")) {
          insert.setString(1, environment);
          insert.setString(2, application);
          insert.setString(3, endpoint.path());
          insert.setString(4, endpoint.upstream().host());
          insert.setInt(5, endpoint.upstream().port());
          insert.setInt(6, ordinal++);
          insert.executeUpdate();
        }
      }
      for (NavigationEntry entry : snapshot.navigation()) {
        try (PreparedStatement insert =
            connection.prepareStatement(
                "insert into edge_navigation_entry (environment_name, application_name, slot, label, \"position\", subpath) values (?, ?, ?, ?, ?, ?)")) {
          insert.setString(1, environment);
          insert.setString(2, application);
          insert.setString(3, entry.slot());
          insert.setString(4, entry.label());
          insert.setInt(5, entry.position());
          insert.setString(6, entry.subpath());
          insert.executeUpdate();
        }
      }
      // This is after every statement that can fail. The durable event listener only sees success
      // once this method returns; a later restart loads PostgreSQL again, never this cache.
      view = readAll(connection);
      return true;
    } catch (SQLException failure) {
      throw new IllegalStateException("could not replace the edge routing projection", failure);
    }
  }

  private static void validateSnapshot(String environment, String application, Snapshot snapshot) {
    if (environment == null
        || environment.isBlank()
        || application == null
        || application.isBlank()) {
      throw new IllegalArgumentException(
          "A deployment route snapshot needs environment and application.");
    }
    if (snapshot == null || snapshot.endpoints() == null) {
      throw new IllegalArgumentException("A deployment route snapshot did not carry endpoints.");
    }
    for (EdgeEndpoint endpoint : snapshot.endpoints()) {
      if (!environment.equals(endpoint.environment())
          || !application.equals(endpoint.application())) {
        throw new IllegalArgumentException("An endpoint is not owned by its deployment snapshot.");
      }
    }
    if (snapshot.browserHost() != null) {
      if (!HostEnvironments.isLabel(snapshot.browserHost())) {
        throw new IllegalArgumentException(
            "`" + snapshot.browserHost() + "` cannot be a DNS label, so it cannot be a host.");
      }
      if (snapshot.endpoints().isEmpty()) {
        // The host resolves to the primary route's upstream, so an application with no route has
        // nothing for its own name to reach.
        throw new IllegalArgumentException(
            "`" + application + "` published a host and no route to serve it from.");
      }
    }
    if (snapshot.apiDocsPath() != null) {
      // The same rule the spec parser enforces, restated where a hand-crafted frame could skip it:
      // a document is reachable only under one of the routes this snapshot itself declares.
      String path = snapshot.apiDocsPath();
      if (path.length() > API_DOCS_LIMIT
          || !path.startsWith("/")
          || snapshot.endpoints().stream()
              .noneMatch(
                  endpoint ->
                      path.equals(endpoint.path())
                          || path.startsWith(
                              endpoint.path().endsWith("/")
                                  ? endpoint.path()
                                  : endpoint.path() + "/"))) {
        throw new IllegalArgumentException(
            "`"
                + application
                + "` published the api-docs path `"
                + path
                + "`, which sits under none of its own routes.");
      }
    }
    Set<String> slots = new LinkedHashSet<>();
    for (NavigationEntry entry : snapshot.navigation()) {
      if (!slots.add(entry.slot())) {
        throw new IllegalArgumentException(
            "`" + application + "` published two placements in " + entry.slot() + ".");
      }
    }
    if (snapshot.browserHost() == null && !snapshot.navigation().isEmpty()) {
      // A placement is a link to a service's own name, so a new-shape frame that forgot its host
      // would publish an entry pointing nowhere. The one exception is the shape that never had a
      // host: an old frame's single label, which the subscriber maps to `system` and the document
      // renders against the environment origin.
      boolean legacy =
          snapshot.navigation().size() == 1
              && SYSTEM_SLOT.equals(snapshot.navigation().get(0).slot());
      if (!legacy) {
        throw new IllegalArgumentException(
            "`" + application + "` published navigation placements and no host to send them to.");
      }
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

  private static void rejectPathConflicts(
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

  /**
   * A public name belongs to one service. The unique index is the belt; this is the refusal that
   * carries a reason, and it keeps the frame poison rather than an SQL error.
   */
  private static void rejectHostConflict(
      Connection connection, String environment, String application, String host)
      throws SQLException {
    if (host == null) {
      return;
    }
    try (PreparedStatement existing =
        connection.prepareStatement(
            "select application_name from edge_deployment_snapshot where environment_name = ? and browser_host = ? and application_name <> ?")) {
      existing.setString(1, environment);
      existing.setString(2, host);
      existing.setString(3, application);
      try (ResultSet result = existing.executeQuery()) {
        if (result.next()) {
          throw new IllegalArgumentException(
              "The "
                  + environment
                  + " host `"
                  + host
                  + "` already belongs to "
                  + result.getString(1)
                  + ".");
        }
      }
    }
  }

  private View readAll() {
    try (Connection connection = dataSource.getConnection()) {
      return readAll(connection);
    } catch (SQLException failure) {
      throw new IllegalStateException("could not load the edge routing projection", failure);
    }
  }

  /** One application's snapshot as it comes back out of PostgreSQL. */
  private static final class Read {

    private String host;
    private String apiDocsPath;
    private EdgeEndpoint primary;
    private int primaryOrdinal = Integer.MAX_VALUE;
  }

  private static View readAll(Connection connection) throws SQLException {
    Map<String, List<EdgeEndpoint>> routes = new LinkedHashMap<>();
    Map<String, Map<String, Read>> applications = new LinkedHashMap<>();
    try (PreparedStatement query =
            connection.prepareStatement(
                "select environment_name, application_name, browser_host, api_docs_path from edge_deployment_snapshot");
        ResultSet result = query.executeQuery()) {
      while (result.next()) {
        Read read =
            applications
                .computeIfAbsent(result.getString(1), ignored -> new LinkedHashMap<>())
                .computeIfAbsent(result.getString(2), ignored -> new Read());
        read.host = result.getString(3);
        read.apiDocsPath = result.getString(4);
      }
    }
    try (PreparedStatement query =
            connection.prepareStatement(
                "select environment_name, application_name, path, upstream_host, upstream_port, ordinal from edge_endpoint order by environment_name, length(path) desc, path");
        ResultSet result = query.executeQuery()) {
      while (result.next()) {
        EdgeEndpoint endpoint =
            new EdgeEndpoint(
                result.getString(1),
                result.getString(2),
                result.getString(3),
                new Upstream(result.getString(4), result.getInt(5)));
        routes.computeIfAbsent(endpoint.environment(), ignored -> new ArrayList<>()).add(endpoint);
        Read read =
            applications
                .computeIfAbsent(endpoint.environment(), ignored -> new LinkedHashMap<>())
                .computeIfAbsent(endpoint.application(), ignored -> new Read());
        int ordinal = result.getInt(6);
        if (ordinal < read.primaryOrdinal) {
          read.primaryOrdinal = ordinal;
          read.primary = endpoint;
        }
      }
    }

    Map<String, Map<String, ServiceHost>> byName = new LinkedHashMap<>();
    Map<String, Map<String, ServiceHost>> byApplication = new LinkedHashMap<>();
    Map<String, Map<String, String>> primaryPaths = new LinkedHashMap<>();
    Map<String, Map<String, String>> apiDocsPaths = new LinkedHashMap<>();
    applications.forEach(
        (environment, reads) ->
            reads.forEach(
                (application, read) -> {
                  if (read.primary != null) {
                    primaryPaths
                        .computeIfAbsent(environment, ignored -> new LinkedHashMap<>())
                        .put(application, read.primary.path());
                  }
                  if (read.apiDocsPath != null) {
                    apiDocsPaths
                        .computeIfAbsent(environment, ignored -> new LinkedHashMap<>())
                        .put(application, read.apiDocsPath);
                  }
                  if (read.host == null || read.primary == null) {
                    return;
                  }
                  ServiceHost host =
                      new ServiceHost(
                          application, read.host, read.primary.upstream(), read.primary.path());
                  byName
                      .computeIfAbsent(environment, ignored -> new LinkedHashMap<>())
                      .put(host.host(), host);
                  byApplication
                      .computeIfAbsent(environment, ignored -> new LinkedHashMap<>())
                      .put(application, host);
                }));

    Map<String, List<NavigationPlacement>> navigation = new LinkedHashMap<>();
    try (PreparedStatement query =
            connection.prepareStatement(
                "select environment_name, application_name, slot, label, \"position\", subpath from edge_navigation_entry");
        ResultSet result = query.executeQuery()) {
      while (result.next()) {
        String environment = result.getString(1);
        String application = result.getString(2);
        Read read =
            applications.getOrDefault(environment, Map.of()).getOrDefault(application, new Read());
        navigation
            .computeIfAbsent(environment, ignored -> new ArrayList<>())
            .add(
                new NavigationPlacement(
                    application,
                    result.getString(3),
                    result.getString(4),
                    result.getInt(5),
                    read.host,
                    read.primary == null ? null : read.primary.path(),
                    result.getString(6)));
      }
    }
    Comparator<NavigationPlacement> order =
        Comparator.comparingInt((NavigationPlacement placement) -> SLOTS.indexOf(placement.slot()))
            .thenComparingInt(NavigationPlacement::position)
            .thenComparing(NavigationPlacement::label)
            .thenComparing(NavigationPlacement::application);
    navigation.values().forEach(placements -> placements.sort(order));

    return new View(
        immutable(routes),
        immutableMaps(byName),
        immutableMaps(byApplication),
        immutablePaths(primaryPaths),
        immutablePaths(apiDocsPaths),
        immutable(navigation));
  }

  private static <T> Map<String, List<T>> immutable(Map<String, List<T>> values) {
    Map<String, List<T>> copy = new LinkedHashMap<>();
    values.forEach((key, list) -> copy.put(key, List.copyOf(list)));
    return Map.copyOf(copy);
  }

  private static Map<String, Map<String, String>> immutablePaths(
      Map<String, Map<String, String>> values) {
    Map<String, Map<String, String>> copy = new LinkedHashMap<>();
    values.forEach((key, map) -> copy.put(key, Map.copyOf(map)));
    return Map.copyOf(copy);
  }

  private static Map<String, Map<String, ServiceHost>> immutableMaps(
      Map<String, Map<String, ServiceHost>> values) {
    Map<String, Map<String, ServiceHost>> copy = new LinkedHashMap<>();
    values.forEach((key, map) -> copy.put(key, Map.copyOf(map)));
    return Map.copyOf(copy);
  }
}
