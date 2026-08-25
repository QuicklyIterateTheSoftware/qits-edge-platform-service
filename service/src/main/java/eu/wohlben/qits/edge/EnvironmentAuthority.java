package eu.wohlben.qits.edge;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The origin an environment's names are built from, read off one request.
 *
 * <p>{@link HostEnvironments} answers "which environment is this name?"; this answers "what do that
 * environment's OTHER names look like?" — everything the navigation document and the environment
 * vhost's redirects need to write {@code https://ci.dev.example.com} without being told the domain.
 *
 * <p>A request names an environment and an apex, and the answer is built from those two:
 *
 * <pre>
 *   dev.example.com          environment at position 0   -> environment dev, apex example.com
 *   ci.dev.example.com       environment at position 1   -> environment dev, apex example.com
 *   example.com, 127.0.0.1   apex, unknown name, address -> the default environment, at the
 *   (no Host at all)                                        canonical origin's apex
 * </pre>
 *
 * <p><b>The default environment's authority is the APEX, with no label of its own.</b> That
 * environment's door is {@code example.com} — where a browser lands — so its services are {@code
 * ci.example.com}, including the login page's {@code idp.example.com}, and the long spelling {@code
 * ci.dev.example.com} stays a valid name for the same place. Every OTHER environment keeps its
 * label: {@code prod.example.com}, {@code ci.prod.example.com}.
 *
 * <p><b>An apex of one label is never shortened</b>, which is the whole of the local case. {@code
 * localhost} alone names every environment at once, so a developer's platform stays at {@code
 * dev.localhost:8080} and {@code ci.dev.localhost:8080}.
 *
 * <p><b>The port is part of the answer</b>, which is what makes {@code
 * http://ci.dev.localhost:8080} work: a developer's whole platform is one port, so an origin
 * without it names nothing.
 *
 * <p><b>The scheme comes from {@code X-Forwarded-Proto} when there is one</b>, because a TLS
 * terminator in front of the edge is the only hop that knows the answer — the same reason {@code
 * EdgeHeaders} sets that header only when it is absent. The FIRST value is read: the header is a
 * list, oldest hop first, and the outermost hop is the one that faced the client.
 *
 * <p>Framework-free and static on purpose, next to {@link HostEnvironments} and for the same
 * reason: this is one of the two pieces of behaviour worth asserting without booting anything, and
 * {@code EnvironmentAuthorityTest} is where the edge cases live.
 */
public record EnvironmentAuthority(String scheme, String authority) {

  /**
   * Where the environment vhost itself is: {@code https://example.com} for the default environment,
   * {@code https://prod.example.com} for every other.
   */
  public String origin() {
    return scheme + "://" + authority;
  }

  /** {@code https://ci.example.com} — where one service's own name is. */
  public String hostOrigin(String host) {
    return scheme + "://" + host + "." + authority;
  }

  /**
   * @param host the request's {@code Host} or {@code :authority}, port and all; null is a request
   *     that carried neither
   * @param forwardedProto the {@code X-Forwarded-Proto} header, or null
   * @param requestScheme the scheme this process was reached over, when nothing forwarded one
   * @param environments the routable environment names
   * @param defaultEnvironment where an unmatched name goes, and the one environment whose names
   *     carry no label of their own
   * @param canonicalAuthority {@code qits.edge.sessions.canonical-origin}'s authority, or null
   */
  public static EnvironmentAuthority of(
      String host,
      String forwardedProto,
      String requestScheme,
      Collection<String> environments,
      String defaultEnvironment,
      String canonicalAuthority) {
    return new EnvironmentAuthority(
        scheme(forwardedProto, requestScheme),
        authority(host, names(environments), defaultEnvironment, canonicalAuthority));
  }

  private static String scheme(String forwardedProto, String requestScheme) {
    if (forwardedProto != null) {
      String first = forwardedProto.split(",")[0].strip().toLowerCase(Locale.ROOT);
      if (first.equals("http") || first.equals("https")) {
        return first;
      }
    }
    String scheme = requestScheme == null ? null : requestScheme.strip().toLowerCase(Locale.ROOT);
    return "https".equals(scheme) ? "https" : "http";
  }

  private static String authority(
      String host, Set<String> environments, String defaultEnvironment, String canonicalAuthority) {
    String name = name(host);
    String port = port(host);
    if (!name.isEmpty() && !isAddressLiteral(name)) {
      String[] labels = name.split("\\.", -1);
      // Position 1 first, the same tie-break as HostEnvironments: an app-shaped name is the
      // likelier reading, and its environment is the second label rather than the first.
      if (labels.length > 1 && environments.contains(labels[1])) {
        String apex = name.substring(labels[0].length() + labels[1].length() + 2);
        return emit(labels[1], apex, defaultEnvironment, port);
      }
      if (environments.contains(labels[0])) {
        return emit(labels[0], name.substring(labels[0].length() + 1), defaultEnvironment, port);
      }
    }
    // The apex, an address literal, a name nobody configured, or no Host at all. None of them says
    // which environment it is, so the answer is the DEFAULT one at the configured origin — the
    // door, which is the one name a deployment always states.
    String fallback = canonicalAuthority == null ? "" : canonicalAuthority.strip();
    if (fallback.isEmpty()) {
      return defaultEnvironment;
    }
    String canonicalName = name(fallback);
    String[] labels = canonicalName.split("\\.", -1);
    // The canonical origin is spelled either way by different deployments. Its default-environment
    // label comes off only when an apex is left underneath: `dev.wohlben.eu` yields `wohlben.eu`,
    // while `dev.localhost` yields `localhost`, which names every environment at once and is
    // therefore no apex at all.
    String apex =
        labels.length > 2 && labels[0].equals(defaultEnvironment)
            ? canonicalName.substring(labels[0].length() + 1)
            : canonicalName;
    return emit(defaultEnvironment, apex, defaultEnvironment, port(fallback));
  }

  /**
   * One environment's authority, built from the environment and the apex behind it.
   *
   * <p>The DEFAULT environment drops its label, because the apex is its door. Every other
   * environment keeps one. And an apex of a single label keeps it too, whatever the environment:
   * {@code localhost} alone names every environment at once, so shortening there would lose the one
   * fact the name carries.
   */
  private static String emit(
      String environment, String apex, String defaultEnvironment, String port) {
    boolean apexIsADomain = apex.indexOf('.') >= 0;
    return (environment.equals(defaultEnvironment) && apexIsADomain
            ? apex
            : environment + "." + apex)
        + port;
  }

  /** Lower case, no surrounding space, no trailing root dot, no port, no IPv6 brackets. */
  private static String name(String host) {
    if (host == null) {
      return "";
    }
    String name = host.strip().toLowerCase(Locale.ROOT);
    if (name.startsWith("[")) {
      int end = name.indexOf(']');
      return end < 0 ? name.substring(1) : name.substring(1, end);
    }
    int colon = name.lastIndexOf(':');
    if (colon >= 0 && name.indexOf(':') == colon) {
      name = name.substring(0, colon);
    }
    while (name.endsWith(".")) {
      name = name.substring(0, name.length() - 1);
    }
    return name;
  }

  /** The {@code :8080} of the request's own name, or an empty string when it carried none. */
  private static String port(String host) {
    if (host == null) {
      return "";
    }
    String name = host.strip();
    int bracket = name.indexOf(']');
    int colon = bracket >= 0 ? name.indexOf(':', bracket) : name.lastIndexOf(':');
    if (colon < 0 || (bracket < 0 && name.indexOf(':') != colon)) {
      return "";
    }
    String port = name.substring(colon);
    for (int i = 1; i < port.length(); i++) {
      if (port.charAt(i) < '0' || port.charAt(i) > '9') {
        return "";
      }
    }
    return port.length() > 1 ? port : "";
  }

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

  private static Set<String> names(Collection<String> environments) {
    Set<String> names = new LinkedHashSet<>();
    for (String environment : environments) {
      if (environment != null && !environment.isBlank()) {
        names.add(environment.strip().toLowerCase(Locale.ROOT));
      }
    }
    return names;
  }
}
