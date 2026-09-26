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
 * example.co.uk} is two labels of domain and {@code localhost} is one — so it is a value, and it is
 * stated once as {@code qits.edge.domain} ({@code QITS_DOMAIN}), which {@code EdgeRouter} reads
 * directly. An address literal, a missing Host header and the domain itself carry no position at
 * all, and all three are answered as the apex is: a door, which serves nothing — that is how the
 * platform is reached before DNS exists.
 *
 * <p><b>A name OUTSIDE the stated domain is a MACHINE NAME, and it is read too.</b> The platform's
 * own machine vhosts are docker network aliases of this container and are deliberately not under
 * the public domain — {@code registry.dev.localhost:8080} is in every image reference, {@code
 * mirror.dev.localhost:8080} in every maven build, {@code githost.dev.localhost:8080} and {@code
 * githost.dev.internal:8080} in every clone from a container — so refusing them takes docker pulls,
 * dependency resolution and container git down together, which is what happened when this class
 * first read the domain. Such a name is therefore read as {@code <app>[.<env>].<machine-suffix>}:
 * the leftmost label is joined against the configured application set exactly as an app label under
 * the domain is, an environment label may follow it, and everything after that is a suffix nobody
 * here enumerates — {@code localhost}, {@code internal}, and whatever else somebody aliases this
 * container as.
 *
 * <p>Two things that reading is NOT. It is not a revival of the tie-breaks the positional grammar
 * retired: under the stated domain the project label stays mandatory and every label keeps being
 * read by position alone, so {@code registry.dev.<domain>} is the 404 it became and no machine
 * reading rescues it. And it carries no project — there is no project tier in a name outside the
 * domain and there never can be — so {@link Route#project()} is null, {@code EnvironmentAuthority}
 * composes nothing from it, and a leading {@link #LANDING} label, which means a project's root, is
 * {@link Reading#UNKNOWN_MACHINE_NAME} rather than an address. A machine name whose leftmost label
 * no application claims is that same 404, and a machine name of a single label — {@code localhost},
 * this container's own service alias — has no app label in it at all and stays a door.
 *
 * <p><b>{@code landing} is a reserved label: it means THIS PROJECT'S ROOT.</b> A deployable writes
 * {@code host: landing} in its {@code .config/qits/deployments.yml} and the deployment it publishes
 * answers the project's own door — {@code <project>.<domain>} — rather than a name of its own. The
 * door is the front of the product, and what answers there is an ordinary deployment.
 *
 * <p>Three consequences, and all three are positional:
 *
 * <ul>
 *   <li>a project door whose environment has a landing publisher is an APPLICATION position, not a
 *       door. The label is joined on exactly as an app label is — see {@code landingEnvironments}
 *       below and {@code EdgeRouter.target}, which resolves it against the same projection.
 *   <li>{@code landing.<project>.<domain>} is NOT a second address for it. A reserved label at an
 *       app position is {@link Reading#RESERVED_LABEL}, which is a 404: a thing that already has an
 *       address does not get a second origin, a second cookie scope and a second thing to keep in
 *       step.
 *   <li>where nothing published it, the door is a door and the built-in redirect stays.
 * </ul>
 *
 * <p>Nothing here asks what KIND of repository published the name. {@code <project>-landing-app}
 * and {@code <project>-landing-service} claim the door identically, because both publish the same
 * label and the label is all that reaches the wire — the convention is held by naming and review.
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
   * The reserved host label that means "this project's root": a deployment publishing it answers
   * {@code <project>.<domain>} rather than a name of its own.
   *
   * <p>It is DECLARED, in a deployable's {@code host:}, and that is the whole of the claim. The
   * repository's role — {@code <project>-landing-app}, {@code <project>-landing-service} — reaches
   * nothing on the wire, so nothing here can ask about it and nothing here does.
   */
  public static final String LANDING = "landing";

  /**
   * Which of the grammar's positions a name landed on. It is the whole answer: {@code EdgeRouter}
   * switches on it rather than inferring the reading from which components happen to be null.
   */
  public enum Reading {

    /**
     * The domain itself, an address literal, a missing Host header, or a single-label machine name
     * such as this container's own service alias. None of them carries a readable position, and all
     * of them are the default environment's door — which serves nothing.
     */
    APEX,

    /** {@code <project>.<domain>} — a project's own door, which also serves nothing. */
    PROJECT_DOOR,

    /**
     * {@code <project>.<domain>} in a project that has environments and whose DEFAULT environment
     * has a landing publisher. Each environment runs its own landing, like every other application,
     * so the bare project name cannot serve one of them — it is a door that sends {@code GET /} to
     * the default environment's instead of to the platform's own front page.
     */
    PROJECT_LANDING_DOOR,

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
     * A reserved label at an app position: {@code landing.<project>.<domain>}. The label means the
     * project's root, which already has an address, so it is never a second one — a 404 no
     * projection can rescue, whether or not anything published it.
     */
    RESERVED_LABEL,

    /**
     * A name the grammar does not describe at all: too many labels, or an environment label a
     * project that has environments does not have. An ordinary 404 that no projection can rescue.
     */
    UNREADABLE,

    /**
     * A machine name — a name outside the stated domain — whose leftmost label is no configured
     * application. A 404, and a flat one: the machine names are this container's own aliases, so
     * the configured application set is the whole of what they may reach and the deployment
     * projection is not asked. A leading {@link #LANDING} label lands here too, because it means a
     * project's root and a name outside the domain is inside no project.
     */
    UNKNOWN_MACHINE_NAME
  }

  /**
   * Where one Host name goes: an environment always, an application when the name asked for one, a
   * project when the name named one, and the position it was read at.
   *
   * @param environment the environment, never null — the app's or the door's environment when the
   *     name states one, and the default when the grammar gives it none to state
   * @param app the configured application the name reached, or null for a door and for a label this
   *     configuration does not know
   * @param unknownApp the label at an app position that this configuration does not route, carried
   *     rather than discarded so the answer can name it. Whether the deployment projection may
   *     still claim it is the {@code reading}'s to say and never this field's: {@link
   *     Reading#UNKNOWN_APP} is the join, and {@link Reading#UNKNOWN_MACHINE_NAME} is a 404 that is
   *     answered before the join is made.
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

    static Route projectLandingDoor(String environment, String project) {
      return new Route(environment, null, null, project, Reading.PROJECT_LANDING_DOOR);
    }

    static Route reservedLabel(String environment, String project) {
      return new Route(environment, null, null, project, Reading.RESERVED_LABEL);
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
     * A machine name nothing configured claims. It carries the label it read, so the 404 can name
     * what it looked for — and no project, because a name outside the domain is inside none.
     */
    static Route unknownMachineName(String environment, String label) {
      return new Route(environment, null, label, null, Reading.UNKNOWN_MACHINE_NAME);
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
     * a project. A door routes nothing and serves nothing — it only ever answers {@code GET /} with
     * a redirect to somewhere that does.
     */
    public boolean toDoor() {
      return reading == Reading.APEX
          || reading == Reading.PROJECT_DOOR
          || reading == Reading.PROJECT_LANDING_DOOR
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
   * Where a Host name goes for a caller that has not read the deployment projection: no project
   * door is claimed, so every one of them is the built-in door. It is what {@code
   * EnvironmentAuthority} composes against, where only the position matters and never who serves
   * it.
   *
   * @param projects the projects that exist right now, slug to whether that project has a tier of
   *     environments under it — {@code EdgeProjects.projects()}. A parameter rather than state
   *     because it moves with the event stream while this object is built once, at boot, from
   *     configuration that does not.
   */
  public Route route(String host, Map<String, Boolean> projects) {
    return route(host, projects, Set.of());
  }

  /**
   * Where a Host name goes, in full.
   *
   * @param host a Host header or HTTP/2 {@code :authority} value; a port suffix, a trailing dot and
   *     letter case are all tolerated, and {@code null} is the same as a name outside the domain
   * @param projects the projects that exist right now, slug to whether that project has a tier of
   *     environments under it — {@code EdgeProjects.projects()}
   * @param landingEnvironments the environments in which some deployment published the reserved
   *     {@link #LANDING} label — {@code EdgeRoutes.landingEnvironments()}. This is THE JOIN: a
   *     project door is a door until a deployment claims it, exactly as an app label is a 404 until
   *     one does, and it is the same projection answering both. A per-call parameter for the same
   *     reason {@code projects} is: it moves with the event stream while this object is built once
   *     at boot.
   */
  public Route route(String host, Map<String, Boolean> projects, Set<String> landingEnvironments) {
    String name = normalise(host);
    if (name.isEmpty() || isAddressLiteral(name) || name.equals(domain)) {
      return Route.apex(defaultEnvironment);
    }
    if (!name.endsWith("." + domain)) {
      // Outside the stated domain, so there is no project tier to read — but the platform's own
      // machine vhosts live exactly here, and they are how docker, maven and git reach it.
      return machineName(name);
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
      if (!landingEnvironments.contains(defaultEnvironment)) {
        // Nothing published the reserved label, so the door is the built-in one.
        return Route.projectDoor(defaultEnvironment, project);
      }
      // An env-less project is deployed once, so its landing IS this name and serves it directly.
      // An env-supporting one runs a landing per tier, like every other application, so the bare
      // name is a door that sends a browser to the default environment's.
      return supportsEnvironments
          ? Route.projectLandingDoor(defaultEnvironment, project)
          : landingRoute(defaultEnvironment, project);
    }
    if (labels.length == 2) {
      if (!supportsEnvironments) {
        // The project has no environment tier, so the label in front of it is an application and
        // that application is served in the default environment — a project like the platform's own
        // is deployed once, not once per environment.
        return appRoute(defaultEnvironment, labels[0], project);
      }
      if (!environments.contains(labels[0])) {
        return Route.unreadable(defaultEnvironment, project);
      }
      // This environment's own door, which that environment's landing deployment serves when there
      // is one.
      return landingEnvironments.contains(labels[0])
          ? landingRoute(labels[0], project)
          : Route.environmentDoor(labels[0], project);
    }
    // Three labels: <app>.<env>.<project>.
    if (!supportsEnvironments || !environments.contains(labels[1])) {
      return Route.unreadable(defaultEnvironment, project);
    }
    return appRoute(labels[1], labels[0], project);
  }

  /**
   * A name outside the stated domain, read as {@code <app>[.<env>].<machine-suffix>}.
   *
   * <p>This is the one reading that joins a label against a configured set rather than deciding it
   * by position, and it has to be: the suffix is a docker network alias — {@code localhost}, {@code
   * internal}, whatever else this container is aliased as — so there is nothing to count labels
   * from the right of, and the application set is all that is left to recognise a name by. It is
   * also what keeps the reading closed: only a name this deployment already configured an
   * application for reaches anything, and the rest is a 404 rather than a fall-through.
   *
   * <p>The environment label is optional and is read only when a suffix follows it, so {@code
   * registry.dev.localhost} is dev's registry while {@code mirror.localhost} is the mirror in the
   * default environment — which is right for a platform service deployed once, and is the same
   * default an env-less project's applications get under the domain.
   *
   * <p>No project, ever. There is no project tier in a name outside the domain, so the reserved
   * {@link #LANDING} label — which means a project's root — is a 404 here and not a door.
   */
  private Route machineName(String name) {
    String[] labels = name.split("\\.", -1);
    if (labels.length < 2) {
      // One label and no suffix: this container's own service alias, or a name a client shortened.
      // There is no app label in it, so it is a door exactly as the apex and an address are.
      return Route.apex(defaultEnvironment);
    }
    String app = labels[0];
    // Only when something follows it. In `mirror.localhost` the second label IS the suffix, and a
    // suffix that happens to be spelled like an environment is still the suffix.
    String environment =
        labels.length > 2 && environments.contains(labels[1]) ? labels[1] : defaultEnvironment;
    if (LANDING.equals(app) || !apps.contains(app)) {
      return Route.unknownMachineName(environment, leading(app));
    }
    return Route.app(environment, app, null);
  }

  /**
   * One application position, resolved: the configured vhost, or the label carried on for the
   * deployment-projection join the caller makes before it answers 404.
   */
  private Route appRoute(String environment, String label, String project) {
    if (LANDING.equals(label)) {
      // The reserved label at an app position. It is not a second address for the deployment that
      // claimed the door, and it is not an app label either — it is a name that means a place the
      // grammar already spells another way, so it is answered as a name outside the grammar is.
      return Route.reservedLabel(environment, project);
    }
    return apps.contains(label)
        ? Route.app(environment, label, project)
        : Route.unknownApp(environment, leading(label), project);
  }

  /**
   * A door position a landing deployment claimed. It is carried as the label the deployment
   * projection is asked about — the SAME position an unconfigured app label lands on — so {@code
   * EdgeRouter.target} resolves it through the one join it already makes rather than through a
   * second mechanism of its own. A claim that has been withdrawn between the two reads of the
   * projection is answered exactly as any unclaimed label is, with a 404 that the next request no
   * longer gets.
   */
  private static Route landingRoute(String environment, String project) {
    return Route.unknownApp(environment, LANDING, project);
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
