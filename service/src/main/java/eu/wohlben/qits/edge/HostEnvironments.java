package eu.wohlben.qits.edge;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The whole of the edge's routing logic: a Host name in, a {@link Route} out.
 *
 * <p><b>The grammar is read RIGHT TO LEFT, and every label is inside the one to its right:</b>
 *
 * <pre>
 *   &lt;app&gt; [ .&lt;env&gt; ] .&lt;project&gt; .&lt;domain&gt;
 *
 *   wohlben.eu                     the apex, which serves nothing
 *   qits.wohlben.eu                the qits project's door
 *   projects.qits.wohlben.eu       an app of the qits project, which supports no environments
 *   editor.qits.wohlben.eu         the editor — an app of qits, like any other
 *   someproject.wohlben.eu         another project's door
 *   dev.someproject.wohlben.eu     that project's dev environment door
 *   ci.dev.someproject.wohlben.eu  an app of that project, in dev
 * </pre>
 *
 * <p>The domain holds projects, a project holds its environments, an environment holds its apps.
 * The reading is POSITIONAL: a label's meaning is decided by where it sits, never by which
 * configured set it happens to belong to. That retired every tie-break this class used to carry —
 * an app called {@code prod}, a project called {@code registry} and an environment called {@code
 * acme} are now three ordinary names that collide with nothing, because no two of them are ever
 * read at the same position.
 *
 * <p><b>The project label is mandatory, and that is what makes the grammar unambiguous.</b> There
 * is no unqualified application tier: the platform is simply the project called {@code qits}, so
 * {@code projects.qits.wohlben.eu} is an app of a project exactly as {@code ci.dev.acme.wohlben.eu}
 * is, and an app name can never structurally collide with a project slug. A name with its project
 * label missing is a name with a label missing — an ordinary 404, with no refusal of its own.
 *
 * <p><b>The env label is present exactly when the project supports environments.</b> That is a
 * property of the project, projected from qits-projects onto {@code EdgeProjects}, so the second
 * label from the right is an environment for one project and an application for the next. It is not
 * guessed from the environment list: a project with environments and a name whose env label is not
 * one of them is a 404, and an env-less project's apps are served in the default environment.
 *
 * <p><b>Positional reading needs the domain STATED.</b> It cannot be derived — {@code
 * example.co.uk} is two labels of domain and {@code localhost} is one — so it is a value, and
 * {@code EdgeRouter} takes it from {@code qits.edge.acme.domain} or, failing that, from the
 * canonical origin. A name that does not end with it carries no position that can be read, so it is
 * answered as the apex is: a door, which serves nothing. An address literal and a missing Host
 * header are the same case, and the same answer — that is how the platform is reached before DNS
 * exists.
 *
 * <p><b>An app label is refused when nothing serves it.</b> The last two readings above are aimed
 * at a service, and services are the names the edge authenticates. A fall-through would hand
 * exactly those requests to a hop that does not authenticate them, so an unconfigured app label is
 * {@link Route#unknownApp() unroutable} here and the caller joins the deployment projection on
 * before answering 404. This is a security property rather than a nicety.
 *
 * <p><b>The project projection is a per-call parameter</b> rather than state. It is live — {@code
 * EdgeProjects} projects it from qits-projects' event stream — and this class stays static and
 * framework-free, exactly as the deployment projection is joined on outside it rather than here.
 *
 * <p><b>An unknown project is a name whose answer changes once the projection catches up.</b> It is
 * the one reading that does, now that the grammar is positional: every other label is read by
 * position and nothing the projection learns can move it. {@link Reading#UNKNOWN_PROJECT} is
 * therefore what {@code EdgeRouter} holds back behind {@code ProjectSansBootstrap}'s barrier, and
 * there is no separate sensitivity question to ask any more.
 *
 * <p>Framework-free on purpose: this is the one piece of behaviour worth unit-testing without
 * booting an application, and {@code HostEnvironmentsTest} is where the readings are pinned.
 */
public final class HostEnvironments {

  /**
   * Which of the grammar's positions a name landed on. It is the whole answer: {@code EdgeRouter}
   * switches on it rather than inferring the reading from which components happen to be null.
   */
  public enum Reading {

    /**
     * The domain itself, an address literal, a missing Host header, or a name outside the domain
     * altogether. None of them carries a readable position, and all of them are the default
     * environment's door — which serves nothing.
     */
    APEX,

    /** {@code <project>.<domain>} — a project's own door, which also serves nothing. */
    PROJECT_DOOR,

    /**
     * {@code <env>.<project>.<domain>} — one environment's door inside a project that has
     * environments. A door like the two above.
     */
    ENVIRONMENT_DOOR,

    /** A configured application vhost, at whichever of the two app depths the project has. */
    APP,

    /**
     * An app-shaped label at an app position that configuration does not know. The deployment
     * projection may still claim it, so the caller joins on that before answering 404.
     */
    UNKNOWN_APP,

    /**
     * The project label names no project this edge knows. The only reading a later frame can still
     * change, so it is the one held behind the project catch-up barrier.
     */
    UNKNOWN_PROJECT,

    /**
     * A name the grammar does not describe at all: too many labels, or an environment label a
     * project that has environments does not have. An ordinary 404 that no projection can rescue.
     */
    UNREADABLE
  }

  /**
   * Where one Host name goes: an environment always, an application when the name asked for one, a
   * project when the name named one, and the position it was read at.
   *
   * @param environment the environment, never null — the app's or the door's environment when the
   *     name states one, and the default when the grammar gives it none to state
   * @param app the configured application the name reached, or null for a door and for a label this
   *     configuration does not know
   * @param unknownApp the label at an app position that this configuration does not route, which
   *     the deployment projection may still claim. Carried rather than discarded so the 404 can
   *     name it.
   * @param project the project slug the name named, or null where it named none. Set on the unknown
   *     project reading too, so its 404 can name the label it could not resolve.
   * @param reading which position the name landed on — see {@link Reading}
   */
  public record Route(
      String environment, String app, String unknownApp, String project, Reading reading) {

    /**
     * The three-component shape, for a name with no project label in play: an app vhost when {@code
     * app} is set, an unroutable app label when {@code unknownApp} is, and the apex otherwise.
     *
     * <p>It exists because the callers that only ever build an app route — the auth tests, and the
     * projection join in {@code EdgeRouter} — read better without a position spelled out that the
     * arguments already decide.
     */
    public Route(String environment, String app, String unknownApp) {
      this(
          environment,
          app,
          unknownApp,
          null,
          app != null ? Reading.APP : unknownApp != null ? Reading.UNKNOWN_APP : Reading.APEX);
    }

    static Route apex(String environment) {
      return new Route(environment, null, null, null, Reading.APEX);
    }

    static Route projectDoor(String environment, String project) {
      return new Route(environment, null, null, project, Reading.PROJECT_DOOR);
    }

    static Route environmentDoor(String environment, String project) {
      return new Route(environment, null, null, project, Reading.ENVIRONMENT_DOOR);
    }

    static Route app(String environment, String app, String project) {
      return new Route(environment, app, null, project, Reading.APP);
    }

    static Route unknownApp(String environment, String label, String project) {
      return new Route(environment, null, label, project, Reading.UNKNOWN_APP);
    }

    static Route unknownProject(String environment, String project) {
      return new Route(environment, null, null, project, Reading.UNKNOWN_PROJECT);
    }

    static Route unreadable(String environment, String project) {
      return new Route(environment, null, null, project, Reading.UNREADABLE);
    }

    /**
     * Whether this name reaches a configured application vhost. False is a door, a 404, or a label
     * only the deployment projection can claim.
     */
    public boolean toApp() {
      return reading == Reading.APP;
    }

    /**
     * Whether this name is a door: the apex, a project's own name, or an environment's name inside
     * a project. A door routes nothing and serves nothing.
     */
    public boolean toDoor() {
      return reading == Reading.APEX
          || reading == Reading.PROJECT_DOOR
          || reading == Reading.ENVIRONMENT_DOOR;
    }
  }

  private final Set<String> environments;
  private final String defaultEnvironment;
  private final Set<String> apps;
  private final String domain;

  private HostEnvironments(
      Set<String> environments, String defaultEnvironment, Set<String> apps, String domain) {
    this.environments = environments;
    this.defaultEnvironment = defaultEnvironment;
    this.apps = apps;
    this.domain = domain;
  }

  /** An edge with no application names: no app position reaches anything of its own. */
  public static HostEnvironments of(
      Collection<String> environments, String defaultEnvironment, String domain) {
    return of(environments, defaultEnvironment, Set.of(), domain);
  }

  /**
   * @param environments the routable environment names; blanks are dropped, case is not significant
   * @param defaultEnvironment where a name that states no environment goes; must be one of the
   *     above
   * @param apps the configured application names — {@code qits.edge.apps}' key set
   * @param domain the stated domain every served name ends with: {@code wohlben.eu}, {@code
   *     localhost}. It cannot be derived from a name, so it is configuration — see the class
   *     javadoc.
   * @throws IllegalArgumentException on an empty list, an unusable name, a default outside it, or a
   *     domain that is not a DNS name
   */
  public static HostEnvironments of(
      Collection<String> environments,
      String defaultEnvironment,
      Collection<String> apps,
      String domain) {
    Set<String> names = new LinkedHashSet<>();
    for (String environment : environments) {
      if (environment == null || environment.isBlank()) {
        continue;
      }
      String name = environment.strip().toLowerCase(Locale.ROOT);
      if (!isLabel(name)) {
        throw new IllegalArgumentException(
            "qits.edge.environments holds `"
                + environment
                + "`, which cannot be a DNS label — an environment name reaches DNS as a label of"
                + " the host names inside a project, so it may hold only letters, digits and inner"
                + " hyphens.");
      }
      names.add(name);
    }
    if (names.isEmpty()) {
      throw new IllegalArgumentException(
          "qits.edge.environments is empty — the edge would have nothing to forward to.");
    }
    if (defaultEnvironment == null || defaultEnvironment.isBlank()) {
      throw new IllegalArgumentException("qits.edge.default-environment is not set.");
    }
    String fallback = defaultEnvironment.strip().toLowerCase(Locale.ROOT);
    if (!names.contains(fallback)) {
      throw new IllegalArgumentException(
          "qits.edge.default-environment is `"
              + defaultEnvironment
              + "`, which is not in qits.edge.environments "
              + names
              + ". It is where an env-less project's applications are served, so a default the edge"
              + " cannot reach would break every one of them rather than an edge case.");
    }
    Set<String> appNames = new LinkedHashSet<>();
    for (String app : apps) {
      if (app == null || app.isBlank()) {
        continue;
      }
      String name = app.strip().toLowerCase(Locale.ROOT);
      if (!isLabel(name)) {
        throw new IllegalArgumentException(
            "qits.edge.apps holds `"
                + app
                + "`, which cannot be a DNS label — an application name is the leftmost label of the"
                + " host clients type, so it may hold only letters, digits and inner hyphens.");
      }
      // No collision check against the environment names any more. The reading is positional, so an
      // application and an environment spelled alike are never read at the same position and the
      // name of neither becomes unreachable.
      appNames.add(name);
    }
    String root = domain == null ? "" : domain.strip().toLowerCase(Locale.ROOT);
    while (root.endsWith(".")) {
      root = root.substring(0, root.length() - 1);
    }
    if (root.isEmpty()) {
      throw new IllegalArgumentException(
          "the edge domain is not set. Every name is read right to left from the domain — it cannot"
              + " be derived from a host name, because `example.co.uk` and `localhost` are two"
              + " labels and one — so the edge has to be told what it is.");
    }
    for (String label : root.split("\\.", -1)) {
      if (!isLabel(label)) {
        throw new IllegalArgumentException(
            "the edge domain is `"
                + domain
                + "`, which is not a DNS name: `"
                + label
                + "` cannot be a label.");
      }
    }
    return new HostEnvironments(Set.copyOf(names), fallback, Set.copyOf(appNames), root);
  }

  /** The routable environment names, lower case. */
  public Set<String> environments() {
    return environments;
  }

  /** The routable application names, lower case. */
  public Set<String> apps() {
    return apps;
  }

  /** Where an env-less project's applications are served, and what every door reports. */
  public String defaultEnvironment() {
    return defaultEnvironment;
  }

  /** The stated domain every served name ends with, lower case and with no trailing root dot. */
  public String domain() {
    return domain;
  }

  /**
   * Where a Host name goes for a caller with no projects at all — which is every name outside the
   * apex reading answered as an unknown project, because the project label is mandatory.
   */
  public Route route(String host) {
    return route(host, Map.of());
  }

  /**
   * Where a Host name goes, in full.
   *
   * @param host a Host header or HTTP/2 {@code :authority} value; a port suffix, a trailing dot and
   *     letter case are all tolerated, and {@code null} is the same as a name outside the domain
   * @param projects the projects that exist right now, slug to whether that project has a tier of
   *     environments under it — {@code EdgeProjects.projects()}. A parameter rather than state
   *     because it moves with the event stream while this object is built once, at boot, from
   *     configuration that does not.
   */
  public Route route(String host, Map<String, Boolean> projects) {
    String name = normalise(host);
    if (name.isEmpty() || isAddressLiteral(name) || name.equals(domain)) {
      return Route.apex(defaultEnvironment);
    }
    if (!name.endsWith("." + domain)) {
      // A name that is not inside the stated domain has no position to be read at: the labels are
      // somebody else's grammar. It is the apex reading, which is a door — so it routes nothing,
      // gates nothing and proxies nothing, exactly as an address literal does.
      return Route.apex(defaultEnvironment);
    }
    String[] labels = name.substring(0, name.length() - domain.length() - 1).split("\\.", -1);
    if (labels.length > 3) {
      // <app>.<env>.<project>.<domain> is the deepest name the grammar has. Anything longer is not
      // one label too many at a known position; it is a name nothing here describes.
      return Route.unreadable(defaultEnvironment, null);
    }
    // Rightmost first, because that is the reading: the project is the label next to the domain.
    String project = labels[labels.length - 1];
    if (!projects.containsKey(project)) {
      return Route.unknownProject(defaultEnvironment, leading(project));
    }
    boolean supportsEnvironments = projects.get(project) == null || projects.get(project);
    if (labels.length == 1) {
      return Route.projectDoor(defaultEnvironment, project);
    }
    if (labels.length == 2) {
      if (!supportsEnvironments) {
        // The project has no environment tier, so the label in front of it is an application and
        // that application is served in the default environment — a project like the platform's own
        // is deployed once, not once per environment.
        return appRoute(defaultEnvironment, labels[0], project);
      }
      return environments.contains(labels[0])
          ? Route.environmentDoor(labels[0], project)
          : Route.unreadable(defaultEnvironment, project);
    }
    // Three labels: <app>.<env>.<project>.
    if (!supportsEnvironments || !environments.contains(labels[1])) {
      return Route.unreadable(defaultEnvironment, project);
    }
    return appRoute(labels[1], labels[0], project);
  }

  /**
   * One application position, resolved: the configured vhost, or the label carried on for the
   * deployment-projection join the caller makes before it answers 404.
   */
  private Route appRoute(String environment, String label, String project) {
    return apps.contains(label)
        ? Route.app(environment, label, project)
        : Route.unknownApp(environment, leading(label), project);
  }

  /** A label that is written into an answer, when it is one at all: it comes off the wire. */
  private static String leading(String label) {
    return isLabel(label) ? label : null;
  }

  /** Lower case, no surrounding space, no trailing root dot, no port suffix, no IPv6 brackets. */
  private static String normalise(String host) {
    if (host == null) {
      return "";
    }
    String name = host.strip().toLowerCase(Locale.ROOT);
    if (name.startsWith("[")) {
      // A bracketed IPv6 literal: `[::1]` or `[::1]:8080`. The brackets exist precisely so the
      // address' own colons cannot be read as a port separator, so unwrap before anything else.
      int end = name.indexOf(']');
      return end < 0 ? name.substring(1) : name.substring(1, end);
    }
    int colon = name.lastIndexOf(':');
    if (colon >= 0 && name.indexOf(':') == colon) {
      // Exactly one colon, so it is a port separator rather than an unbracketed IPv6 address.
      name = name.substring(0, colon);
    }
    while (name.endsWith(".")) {
      name = name.substring(0, name.length() - 1);
    }
    return name;
  }

  /**
   * Whether a name is an IP address rather than a domain name. IPv4 is checked by shape rather than
   * by range: a label may legally be all digits, and an address that fell through to the positional
   * reading would be split against a domain it cannot be inside. IPv6 is anything left holding a
   * colon after {@link #normalise}.
   */
  private static boolean isAddressLiteral(String name) {
    if (name.indexOf(':') >= 0) {
      return true;
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c != '.' && (c < '0' || c > '9')) {
        return false;
      }
    }
    return true;
  }

  /**
   * The DNS label charset: letters, digits and hyphens, never leading or trailing.
   *
   * <p>Public because a projected service host is the same kind of name as a configured one — see
   * {@code DeploymentActiveSubscriber} — and one spelling of the rule is the point.
   */
  public static boolean isLabel(String name) {
    if (name.isEmpty() || name.length() > 63 || name.startsWith("-") || name.endsWith("-")) {
      return false;
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
      if (!ok) {
        return false;
      }
    }
    return true;
  }
}
