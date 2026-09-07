package eu.wohlben.qits.edge;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The whole of the edge's routing logic: a Host name in, a {@link Route} out.
 *
 * <p>There are four served spellings, and every one of them states its environment:
 *
 * <pre>
 *   $env.$domain                  prod.example.com                -> prod's door, which serves nothing
 *   $app.$env.$domain             registry.prod.example.com       -> prod's registry, direct
 *   $project.$env.$domain         acme.prod.example.com           -> the acme project's door in prod
 *   $app.$project.$env.$domain    editor.acme.prod.example.com    -> the editor, for acme, in prod
 * </pre>
 *
 * <p><b>The environment label is not optional any more.</b> {@code registry.example.com} used to be
 * the default environment's registry, on the reasoning that the apex is that environment's door.
 * The project tier is what ended it: {@code editor.acme.example.com} and {@code
 * editor.acme.prod.example.com} would be the same place under that rule, so the middle label of a
 * four-label name would have to be read as a project in one spelling and an environment in the
 * other, and {@code acme.example.com} would be a project door or an application vhost depending on
 * which set a label happened to be in. So a name with more than one label that does NOT state its
 * environment is no longer served at all: it is {@link Route#envExplicit() not env-explicit}, and
 * the caller answers 404 naming the explicit spelling. There is no redirect — a short name is a
 * bookmark, a link or a hard-coded string somewhere, and each of those wants fixing rather than
 * papering over.
 *
 * <p><b>The apex itself is the one exception, and this class cannot see it.</b> {@code example.com}
 * and {@code nosuchapp.example.com} are the same shape from the left, and only the configured
 * canonical origin tells them apart — so the apex is rescued by {@code EdgeRouter}, which knows it,
 * and everything here answers alike. A single-label name ({@code localhost}), an address literal
 * and a missing Host header carry no shape at all and stay the default environment's door.
 *
 * <p><b>An app label is refused when nothing serves it.</b> A first label in front of a known
 * environment — or in front of a known project in front of a known environment — is aimed at a
 * service, and services are the names the edge authenticates. A fall-through would hand exactly
 * those requests to a hop that does not authenticate them, so an unconfigured app label is {@link
 * Route#unknownApp() unroutable} here and the caller joins the deployment projection on before
 * answering 404.
 *
 * <p><b>Only the first THREE labels are read</b>, which is what keeps the domain itself out of
 * configuration. It may be one label ({@code prod.localhost}), two ({@code example.com}) or three
 * ({@code example.co.uk}); stating it would be a second thing to keep in step with DNS for no gain,
 * and reading from the left needs no such knowledge.
 *
 * <p><b>The tie-breaks, in the order they are read.</b> An environment at position 1 beats
 * everything: {@code staging.prod.example.com} with both names configured is <i>app {@code staging}
 * in environment {@code prod}</i> rather than <i>environment {@code staging} in a domain starting
 * {@code prod}</i>, and a label that is somehow both an environment and a project is read as the
 * environment (the platform prevents that collision on the far side, so this only decides what
 * happens if the two ever disagree). At position 0 a SERVICE beats a project: a configured
 * application called {@code acme} keeps {@code acme.prod.example.com}, and a project of the same
 * name reaches its door on nothing — which is why the platform refuses the collision rather than
 * relying on this.
 *
 * <p><b>The project set is a per-call parameter</b> rather than state. It is live — {@code
 * EdgeProjects} projects it from qits-projects' event stream — and this class stays static and
 * framework-free, exactly as the deployment projection is joined on outside it rather than here.
 *
 * <p>Framework-free on purpose: this is the one piece of behaviour worth unit-testing without
 * booting an application, and {@code HostEnvironmentsTest} is where the edge cases live.
 */
public final class HostEnvironments {

  /**
   * Where one Host name goes: an environment always, an application when the name asked for one,
   * and a project when the name named one.
   *
   * @param environment the environment, never null — the app's environment when there is an app,
   *     and the default when the name named none
   * @param app the configured application the name reached, or null for a door and for a label this
   *     configuration does not know
   * @param unknownApp the label at position 0 that this configuration does not route: an app-shaped
   *     label in front of a known environment, which the deployment projection may still claim, or
   *     the leading label of a name that states no environment at all. Carried rather than
   *     discarded so the 404 can name it.
   * @param project the project slug the name named, or null. Set on all four project readings — the
   *     project door, the four-label application tier, and the two short spellings of each.
   * @param envExplicit whether the name STATES its environment. False is a name that would have
   *     needed the old default-environment fall-through, and it is not served: the caller answers
   *     404 naming the explicit spelling, having first excepted the apex, which it alone can
   *     recognise.
   */
  public record Route(
      String environment, String app, String unknownApp, String project, boolean envExplicit) {

    /**
     * The three-component shape, for every reading that names no project and states its
     * environment. It exists so the readings that did not change are still written the way they
     * were.
     */
    public Route(String environment, String app, String unknownApp) {
      this(environment, app, unknownApp, null, true);
    }

    static Route environment(String environment) {
      return new Route(environment, null, null);
    }

    /**
     * The project's own door in one environment. The label is carried as the PROJECT rather than as
     * an unknown app because the caller has one question to ask before this is the answer — see
     * {@link #toProjectDoor()}.
     */
    static Route projectDoor(String environment, String project) {
      return new Route(environment, null, null, project, true);
    }

    /**
     * A name that states no environment, in the default environment it would have meant. Never
     * served; the components are here so the answer can name the explicit spelling.
     */
    static Route shortForm(String environment, String label, String project) {
      return new Route(environment, null, label, project, false);
    }

    /**
     * Whether this name reaches a configured application vhost. False is a door — the environment's
     * or a project's — which routes nothing and serves nothing.
     */
    public boolean toApp() {
      return app != null;
    }

    /**
     * Whether this name reaches a project's own door, <b>unless the deployment projection claims
     * the label first</b>.
     *
     * <p>That last clause cannot be decided here: this class knows the configured applications and
     * the project slugs, and a service's published name is neither. So {@code EdgeRouter.target}
     * asks {@code routes.serviceHost(environment, project)} before it believes this — a published
     * service wins the label, and the door is what is left. The same join, in the same place, as
     * the one behind {@link #unknownApp()}.
     */
    public boolean toProjectDoor() {
      return envExplicit && project != null && app == null && unknownApp == null;
    }
  }

  private final Set<String> environments;
  private final String defaultEnvironment;
  private final Set<String> apps;

  private HostEnvironments(Set<String> environments, String defaultEnvironment, Set<String> apps) {
    this.environments = environments;
    this.defaultEnvironment = defaultEnvironment;
    this.apps = apps;
  }

  /** An edge with no application names: {@code $app.$env.$domain} reaches nothing of its own. */
  public static HostEnvironments of(Collection<String> environments, String defaultEnvironment) {
    return of(environments, defaultEnvironment, Set.of());
  }

  /**
   * @param environments the routable environment names; blanks are dropped, case is not significant
   * @param defaultEnvironment where the apex and every unmatched name go; must be one of the above
   * @param apps the configured application names — {@code qits.edge.apps}' key set
   * @throws IllegalArgumentException on an empty list, an unusable name, or a default outside it
   */
  public static HostEnvironments of(
      Collection<String> environments, String defaultEnvironment, Collection<String> apps) {
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
                + "`, which cannot be a DNS label — an environment name reaches DNS as part of its"
                + " gateway's host name, so it may hold only letters, digits and inner hyphens.");
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
              + ". Every unmatched host goes to the default, so a default the edge cannot reach"
              + " would break most of its traffic rather than an edge case of it.");
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
                + "`, which cannot be a DNS label — an application name is the first label of the"
                + " host clients type, so it may hold only letters, digits and inner hyphens.");
      }
      if (names.contains(name)) {
        throw new IllegalArgumentException(
            "`"
                + name
                + "` is both an environment and an application. The tie-break reads the first label"
                + " as an application, so the environment would become unreachable by name.");
      }
      appNames.add(name);
    }
    return new HostEnvironments(Set.copyOf(names), fallback, Set.copyOf(appNames));
  }

  /** The routable environment names, lower case. */
  public Set<String> environments() {
    return environments;
  }

  /** The routable application names, lower case. */
  public Set<String> apps() {
    return apps;
  }

  /** Where the apex domain and every unmatched host go. */
  public String defaultEnvironment() {
    return defaultEnvironment;
  }

  /**
   * The environment a Host name names, or {@link #defaultEnvironment()} when it names none. The
   * answer for an app-shaped name is that app's environment, whether or not the app is configured.
   *
   * @param host a Host header or HTTP/2 {@code :authority} value; a port suffix, a trailing dot and
   *     letter case are all tolerated, and {@code null} is the same as an unmatched name
   */
  public String resolve(String host) {
    return route(host).environment();
  }

  /**
   * Where a Host name goes for a caller with no project set — configuration's own answer.
   *
   * <p>Everything that is not a project reading is decided by configuration alone, so this is the
   * whole answer for {@code $env.$domain} and {@code $app.$env.$domain} and an honest one for the
   * rest: without projects a name in the project tiers is a name that states no environment, which
   * is not served either way.
   */
  public Route route(String host) {
    return route(host, Set.of());
  }

  /**
   * Where a Host name goes, in full: an environment, an application when the name reached one, a
   * project when it named one, and an unroutable label when it named one nothing here serves.
   *
   * @param host a Host header or HTTP/2 {@code :authority} value; a port suffix, a trailing dot and
   *     letter case are all tolerated, and {@code null} is the same as an unmatched name
   * @param projects the project slugs that exist right now — {@code EdgeProjects.slugs()}. A
   *     parameter rather than state because it moves with the event stream while this object is
   *     built once, at boot, from configuration that does not.
   */
  public Route route(String host, Set<String> projects) {
    String name = normalise(host);
    if (name.isEmpty() || isAddressLiteral(name)) {
      // An address literal carries no name to read. It is how the platform is reached before DNS
      // exists — a bootstrap curling the host's own port — and the default is the right answer.
      return Route.environment(defaultEnvironment);
    }
    String[] labels = name.split("\\.", -1);
    // Position 1 first: see the class javadoc for why the longer reading wins a tie, and why an
    // environment there beats a project of the same spelling.
    if (labels.length > 1 && environments.contains(labels[1])) {
      // $app.$env.$domain or $project.$env.$domain. The label in front of a known environment names
      // a service, a project, or nothing — it does NOT fall through to anything, because the hops
      // it would fall through to are the ones that do not authenticate a service's callers.
      if (apps.contains(labels[0])) {
        return new Route(labels[1], labels[0], null);
      }
      return projects.contains(labels[0])
          ? Route.projectDoor(labels[1], labels[0])
          : new Route(labels[1], null, labels[0]);
    }
    if (labels.length > 2 && projects.contains(labels[1]) && environments.contains(labels[2])) {
      // $app.$project.$env.$domain — the tier the web editor is served on. The environment is at
      // position 2, so everything downstream (the audience, the upstream, the origins) resolves
      // against the environment the NAME states rather than against the default.
      return apps.contains(labels[0])
          ? new Route(labels[2], labels[0], null, labels[1], true)
          : new Route(labels[2], null, labels[0], labels[1], true);
    }
    if (environments.contains(labels[0])) {
      return Route.environment(labels[0]);
    }
    if (labels.length > 2 && projects.contains(labels[1])) {
      // $app.$project.$domain: the four-label tier with its environment left out.
      return Route.shortForm(defaultEnvironment, leading(labels[0]), labels[1]);
    }
    if (labels.length > 1 && projects.contains(labels[0])) {
      // $project.$domain: the project door with its environment left out.
      return Route.shortForm(defaultEnvironment, null, labels[0]);
    }
    if (labels.length > 1) {
      // $app.$domain and every other name of more than one label that states no environment. This
      // is where the default-environment fall-through was, and its absence is the point: the name
      // is not served, and the answer names the spelling that is.
      return Route.shortForm(defaultEnvironment, leading(labels[0]), null);
    }
    // One label — `localhost` — names no environment and cannot name one: it is every environment's
    // domain at once. It stays the default environment's door, as does an address and no Host.
    return Route.environment(defaultEnvironment);
  }

  /**
   * Whether this name's reading could still change once more project slugs arrive.
   *
   * <p><b>The project set became a ROUTING input, and it is fed by a projection that starts
   * behind.</b> {@code EdgeProjects} replays qits-projects' log from the epoch at every boot, so
   * there is a window — a deployment catching up, or qits-events being down for this one consumer —
   * in which a slug the platform has exists here and a slug it has does NOT. Every name that
   * consults the set reads differently across that window: {@code editor.acme.dev.example.com} is
   * the editor when {@code acme} is known and a short-form 404 pointing at a name that also 404s
   * when it is not, and {@code acme.dev.example.com} is a project door or an unknown app.
   *
   * <p>So the caller asks this before it answers one of those 404s, and answers {@code 503} while
   * the projection is behind — see {@code EdgeRouter}. A retryable "not yet" is recoverable; a 404
   * naming the wrong spelling is a person filing a bug against a platform that is merely reading.
   *
   * <p><b>Deliberately conservative.</b> It says yes whenever SOME slug set would read this name
   * differently, which includes names whose middle label nobody will ever create. The cost of a
   * false yes is one 503 instead of one 404 during a catch-up; the cost of a false no is the answer
   * this exists to prevent.
   *
   * @param projects the slugs that exist right now — the same set {@link #route(String, Set)} was
   *     given, so this answers about the reading that actually happened
   */
  public boolean projectSensitive(String host, Set<String> projects) {
    String name = normalise(host);
    if (name.isEmpty() || isAddressLiteral(name)) {
      return false;
    }
    String[] labels = name.split("\\.", -1);
    // The readings of route(), in the same order, asking of each one whether a slug could take it.
    if (labels.length > 1 && environments.contains(labels[1])) {
      // $app.$env.$domain. An environment at position 1 beats everything, so the only thing a slug
      // can still change here is the FIRST label: a project there is that project's door rather
      // than an unroutable app. A configured app label already owns it, and a slug already read as
      // one has nothing left to learn.
      return !apps.contains(labels[0]) && !projects.contains(labels[0]) && isLabel(labels[0]);
    }
    if (labels.length > 2 && projects.contains(labels[1])) {
      return false;
    }
    if (labels.length > 2 && environments.contains(labels[2]) && isLabel(labels[1])) {
      // $app.$project.$env.$domain, one slug short of being served at all.
      return true;
    }
    if (environments.contains(labels[0])) {
      // The environment's own door, and no reading below it consults a project.
      return false;
    }
    if (labels.length > 2 && isLabel(labels[1])) {
      // $app.$project.$domain — the short spelling, whose 404 names the explicit host. Which host
      // that is depends on whether the middle label is a project, so the answer moves with the set.
      return true;
    }
    // $project.$domain and $app.$domain are the same 404 either way: both readings name the same
    // explicit host, so a slug arriving changes nothing a caller can see. And one label, an address
    // and no Host at all name no project and never could.
    return false;
  }

  /** The leading label, when it is one at all: it is written into an answer, so it is checked. */
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
   * by range: a label may legally be all digits, so {@code 127.0.0.1} would otherwise be split and
   * compared against environment names, and an environment called {@code 0} would start capturing
   * loopback traffic. IPv6 is anything left holding a colon after {@link #normalise}.
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
