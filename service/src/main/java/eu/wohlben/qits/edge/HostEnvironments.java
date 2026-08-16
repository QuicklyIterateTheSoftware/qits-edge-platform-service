package eu.wohlben.qits.edge;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The whole of the edge's routing logic: a Host name in, a {@link Route} out.
 *
 * <p>The convention is two spellings:
 *
 * <pre>
 *   $env.$domain           prod.example.com          -> prod's deployment endpoints
 *   $app.$env.$domain      registry.prod.example.com -> prod's registry, direct
 * </pre>
 *
 * <p>Everything else — the apex domain, an unknown environment name, an IP address, {@code
 * localhost}, a missing Host header — resolves to the <b>default environment</b>.
 *
 * <p><b>An app label is refused when it is not configured</b>, and that is the one place the rule
 * above does not hold. A name of the shape {@code $app.$env.$domain} — a first label in front of a
 * KNOWN environment — is aimed at a service, and services are the names the edge authenticates. A
 * fall-through to the gateway would hand exactly those requests to the one hop that does not
 * authenticate them, so an unconfigured app label is {@link Route#unknownApp() unroutable} and the
 * edge answers 404. Names that are not app-shaped are untouched by this: {@code
 * staging.example.com} names no known environment at position 1, so it is still the default
 * gateway's.
 *
 * <p><b>Only the first two labels are read</b>, which is what makes the domain itself irrelevant.
 * The edge is never told what {@code $domain} is: it may be one label ({@code prod.localhost}), two
 * ({@code example.com}) or three ({@code example.co.uk}), and stating it in configuration would be
 * a second thing to keep in step with DNS for no gain. Reading from the left needs no such
 * knowledge.
 *
 * <p><b>The tie-break is position 1 before position 0</b>, and it only matters when an environment
 * name is also an application name. {@code staging.prod.example.com} with both {@code staging} and
 * {@code prod} configured reads as <i>app {@code staging} in environment {@code prod}</i>, not as
 * <i>environment {@code staging} in the domain {@code prod.example.com}</i>. An application may be
 * called anything, whereas a domain whose first label happens to be an environment name is a
 * coincidence nobody arranges — so the three-label reading is the likelier one and wins.
 *
 * <p>Framework-free on purpose: this is the one piece of behaviour worth unit-testing without
 * booting an application, and {@code HostEnvironmentsTest} is where the edge cases live.
 */
public final class HostEnvironments {

  /**
   * Where one Host name goes: an environment always, and an application when the name asked for
   * one.
   *
   * @param environment the environment, never null — the app's environment when there is an app
   * @param app the configured application the name reached, or null for that environment's gateway
   * @param unknownApp the app-shaped label that is not configured, or null when the name routes.
   *     Carried rather than discarded so the 404 can name it.
   */
  public record Route(String environment, String app, String unknownApp) {

    static Route environment(String environment) {
      return new Route(environment, null, null);
    }

    /** Whether this name reaches a configured application vhost rather than deployment routing. */
    public boolean toApp() {
      return app != null;
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
   * Where a Host name goes, in full: an environment, an application when the name reached one, and
   * an unroutable app label when it named one the edge does not have.
   *
   * @param host a Host header or HTTP/2 {@code :authority} value; a port suffix, a trailing dot and
   *     letter case are all tolerated, and {@code null} is the same as an unmatched name
   */
  public Route route(String host) {
    String name = normalise(host);
    if (name.isEmpty() || isAddressLiteral(name)) {
      // An address literal carries no name to read. It is how the platform is reached before DNS
      // exists — a bootstrap curling the host's own port — and the default is the right answer.
      return Route.environment(defaultEnvironment);
    }
    String[] labels = name.split("\\.", -1);
    // Position 1 first: see the class javadoc for why the three-label reading wins a tie.
    if (labels.length > 1 && environments.contains(labels[1])) {
      // App-shaped. The label in front of a known environment names a service or it names nothing —
      // it does NOT fall through to the gateway, which is the hop that would serve it
      // unauthenticated.
      String app = labels[0];
      return apps.contains(app) ? new Route(labels[1], app, null) : new Route(labels[1], null, app);
    }
    if (environments.contains(labels[0])) {
      return Route.environment(labels[0]);
    }
    return Route.environment(defaultEnvironment);
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

  /** The DNS label charset: letters, digits and hyphens, never leading or trailing. */
  private static boolean isLabel(String name) {
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
