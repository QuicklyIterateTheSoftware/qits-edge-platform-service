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
 *   dev.example.com              environment at position 0   -> environment dev, apex example.com
 *   ci.dev.example.com           environment at position 1   -> environment dev, apex example.com
 *   acme.dev.example.com         a project's door            -> environment dev, apex example.com
 *   editor.acme.dev.example.com  environment at position 2   -> environment dev, apex example.com
 *   example.com, 127.0.0.1       apex, unknown name, address -> the default environment, at the
 *   (no Host at all)                                            canonical origin's apex
 * </pre>
 *
 * <p><b>Every environment's authority carries its own label, the default one included.</b> {@code
 * prod.example.com} and {@code ci.prod.example.com}, not {@code example.com} and {@code
 * ci.example.com}. The default environment used to drop its label, because the apex is its door —
 * and that is exactly the rule the project tier retired: with a project label in the middle, {@code
 * editor.acme.example.com} and {@code editor.acme.prod.example.com} would be two spellings of one
 * place whose middle label means different things, so {@code HostEnvironments} stopped serving the
 * short spelling and this stopped writing it. Every origin written here is now a name that
 * resolves, in one spelling, whichever environment it is.
 *
 * <p><b>The one deliberate asymmetry is the apex itself.</b> {@code example.com} is still the
 * default environment's door and is still where a browser that types the bare domain lands — it is
 * the only unlabelled name left, it is the canonical origin, and it is how this class knows what
 * the apex IS. It is simply not a name anything derives: the door redirects to a service's own
 * name, which carries the environment label like every other.
 *
 * <p><b>An apex of one label keeps its environment label too</b>, which is the whole of the local
 * case and is now the same rule rather than an exception to one. {@code localhost} alone names
 * every environment at once, so a developer's platform stays at {@code dev.localhost:8080} and
 * {@code ci.dev.localhost:8080}.
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
   * Where the environment vhost itself is: {@code https://prod.example.com}, {@code
   * https://dev.example.com}. Every environment, the default one included — see the class javadoc.
   *
   * <p>It is also what a client prefixes a label onto, which is the whole of {@code
   * projectOrigin}'s contract in the navigation document: {@code editor.<slug>.} in front of this
   * authority is the editor's name.
   */
  public String origin() {
    return scheme + "://" + authority;
  }

  /**
   * {@code https://ci.dev.example.com} — where one service's own name is. A project's door is the
   * same shape and the same call: the slug is a label in front of the environment, exactly as a
   * service's name is.
   */
  public String hostOrigin(String host) {
    return scheme + "://" + host + "." + authority;
  }

  /**
   * {@code https://editor.acme.dev.example.com} — where one application is for one project. Two
   * labels in front of the environment, which is the tier the web editor is served on.
   */
  public String projectHostOrigin(String host, String project) {
    return scheme + "://" + host + "." + project + "." + authority;
  }

  /**
   * @param host the request's {@code Host} or {@code :authority}, port and all; null is a request
   *     that carried neither
   * @param forwardedProto the {@code X-Forwarded-Proto} header, or null
   * @param requestScheme the scheme this process was reached over, when nothing forwarded one
   * @param environments the routable environment names
   * @param defaultEnvironment where an unmatched name goes
   * @param projects the project slugs that exist right now — a per-call parameter for the same
   *     reason as {@link HostEnvironments#route(String, Set)}'s. Without them a four-label name
   *     would read as an unknown name and claim the DEFAULT environment's origins, which is a
   *     navigation document pointing one environment's editor at another's services.
   * @param canonicalAuthority {@code qits.edge.sessions.canonical-origin}'s authority, or null
   */
  public static EnvironmentAuthority of(
      String host,
      String forwardedProto,
      String requestScheme,
      Collection<String> environments,
      String defaultEnvironment,
      Collection<String> projects,
      String canonicalAuthority) {
    return new EnvironmentAuthority(
        scheme(forwardedProto, requestScheme),
        authority(
            host, names(environments), defaultEnvironment, names(projects), canonicalAuthority));
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
      String host,
      Set<String> environments,
      String defaultEnvironment,
      Set<String> projects,
      String canonicalAuthority) {
    String name = name(host);
    String port = port(host);
    if (!name.isEmpty() && !isAddressLiteral(name)) {
      String[] labels = name.split("\\.", -1);
      // The same readings in the same order as HostEnvironments, because the two must never
      // disagree about which environment a name is: a document written for one environment's
      // origins and served by another's routes is worse than either being wrong on its own.
      if (labels.length > 1 && environments.contains(labels[1])) {
        String apex = behind(name, labels[0].length() + labels[1].length() + 2);
        if (apex != null) {
          return emit(labels[1], apex, port);
        }
      }
      if (labels.length > 2 && projects.contains(labels[1]) && environments.contains(labels[2])) {
        // $app.$project.$env.$domain: the apex is what is left after THREE labels. Without this
        // reading `/main-navigation` on the editor's own name would fall through below and claim
        // the default environment — the editor would render dev's tree against prod's origins.
        String apex =
            behind(name, labels[0].length() + labels[1].length() + labels[2].length() + 3);
        if (apex != null) {
          return emit(labels[2], apex, port);
        }
      }
      if (environments.contains(labels[0])) {
        String apex = behind(name, labels[0].length() + 1);
        if (apex != null) {
          return emit(labels[0], apex, port);
        }
      }
    }
    // The apex, an address literal, a name nobody configured, or no Host at all. None of them says
    // which environment it is, so the answer is the DEFAULT one at the configured origin — the
    // door, which is the one name a deployment always states.
    String fallback = canonicalAuthority == null ? "" : canonicalAuthority.strip();
    if (fallback.isEmpty()) {
      return defaultEnvironment;
    }
    return emit(defaultEnvironment, apex(fallback, defaultEnvironment), port(fallback));
  }

  /**
   * What is left of a name after the labels a reading consumed, or null when nothing is.
   *
   * <p><b>Every reading above needs an apex behind it, and a Host header is caller input.</b>
   * {@code prod}, {@code ci.dev}, {@code acme.dev} and {@code editor.acme.dev} each match one of
   * those readings and then END — there is no domain left to build an authority from. These
   * derivations used to be bare {@code substring} calls past the end of the string, so each of
   * those four names was an unauthenticated 500 out of {@code /main-navigation} and out of the
   * door's own redirect: a name that names no site, answered with a stack trace.
   *
   * <p>A reading with nothing behind it falls through, and what it falls through to is the
   * canonical-origin arm below — the same answer an unusable Host gets today. It is the honest one:
   * these names carry a label that says which environment they mean and no domain that says where,
   * and the origins this class writes are absolute URLs a browser has to be able to follow.
   */
  private static String behind(String name, int consumed) {
    return consumed < name.length() ? name.substring(consumed) : null;
  }

  /**
   * The apex behind the canonical origin — the bare domain, which is the door and the one name that
   * carries no environment label.
   *
   * <p>The canonical origin is spelled either way by different deployments — {@code
   * https://wohlben.eu} and {@code https://prod.wohlben.eu} are one deployment's two spellings of
   * one place — so the default environment's own label comes off when it is there. {@code
   * dev.localhost} yields {@code localhost} for the same reason, and the authority derived back
   * from it is {@code dev.localhost} again. A canonical origin that is nothing but that label keeps
   * it, because there would be no name underneath at all.
   *
   * <p>Package-visible because {@code EdgeRouter} asks the same question of the same value: the
   * apex is the one name it may serve without an environment label, and this is how it recognises
   * it.
   */
  static String apex(String canonicalAuthority, String defaultEnvironment) {
    String canonicalName = name(canonicalAuthority);
    String[] labels = canonicalName.split("\\.", -1);
    return labels.length > 1 && labels[0].equals(defaultEnvironment)
        ? canonicalName.substring(labels[0].length() + 1)
        : canonicalName;
  }

  /**
   * One environment's authority, built from the environment and the apex behind it.
   *
   * <p>Every environment keeps its label, the default one included — see the class javadoc for what
   * the project tier did to the short spelling. The apex itself is still the default environment's
   * door; it is simply not derived from anything, it is the origin everything else is derived FROM.
   */
  private static String emit(String environment, String apex, String port) {
    return environment + "." + apex + port;
  }

  /**
   * Lower case, no surrounding space, no trailing root dot, no port, no IPv6 brackets.
   *
   * <p>Package-visible for the same reason {@link #apex} is: {@code EdgeRouter} compares a
   * request's own name against the apex, and a comparison against anything less normalised than
   * this is a name that misses — {@code example.com.} is the apex, spelled the way a resolver
   * spells it.
   */
  static String name(String host) {
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
