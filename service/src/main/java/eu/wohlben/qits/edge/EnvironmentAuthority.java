package eu.wohlben.qits.edge;

import java.util.Locale;
import java.util.Map;

/**
 * The origin one request's own name is composed against, read off that name and the grammar.
 *
 * <p>{@link HostEnvironments} answers "which place is this name?"; this answers "what do that
 * place's OTHER names look like?" — everything the navigation document and a door's redirect need
 * to write {@code https://ci.dev.acme.example.com} without being told the domain twice.
 *
 * <p><b>The grammar is the router's, spelled forwards.</b> A name is read right to left — {@code
 * <app>[.<env>].<project>.<domain>} — so what is composed is the same shape, built back up from the
 * labels the reading resolved:
 *
 * <pre>
 *   ci.dev.acme.example.com   an app of an env-supporting project -> dev.acme.example.com
 *   dev.acme.example.com      that project's environment door     -> dev.acme.example.com
 *   acme.example.com          that project's own door             -> dev.acme.example.com
 *                                                                    (the DEFAULT environment)
 *   projects.qits.example.com an app of an env-LESS project       -> qits.example.com
 *   qits.example.com          that project's door                 -> qits.example.com
 *   example.com, 127.0.0.1    the apex, an address, no Host       -> the canonical origin, read
 *   dev.nosuchproject.example.com  a project nobody created           the same way
 * </pre>
 *
 * <p><b>The authority is the INNERMOST door, and an app label is one label in front of it.</b> That
 * is the whole contract, and it is one rule rather than two because the grammar nests: an
 * env-supporting project's innermost door is its environment's, an env-less project's is its own,
 * and in both cases an application of it is {@code <app>.} in front. Nothing here prefixes a
 * project label onto anything — the authority already carries it.
 *
 * <p><b>{@code supportsEnvironments} decides whether the env label is there at all</b>, and it is
 * read per request from the live projection rather than baked in. The platform's own project is
 * exactly why: it supports environments today, so its addresses are {@code
 * <app>.dev.qits.<domain>}, and they become {@code <app>.qits.<domain>} the moment a {@code
 * ProjectChanged} frame flips the flag. Both are the ordinary behaviour of one rule.
 *
 * <p><b>A name that names no project composes no application name</b>, and says so by answering
 * null from {@link #hostOrigin}. The apex, an address literal, a name outside the domain and a name
 * whose project label names no project all carry no project, and under this grammar there is no
 * application address that does not: every app is inside a project. Such a name falls back to the
 * CANONICAL ORIGIN, read by the same grammar — a deployment that states its origin as its own
 * project's door therefore keeps a front door on the apex, and one that states the bare apex has
 * nothing to compose and the door says so instead of redirecting somewhere that 404s.
 *
 * <p><b>The port is part of the answer</b>, which is what makes {@code
 * http://ci.dev.acme.localhost:8080} work: a developer's whole platform is one port, so an origin
 * without it names nothing. It travels with whichever name was read — the request's own, or the
 * canonical origin's when that is what answered.
 *
 * <p><b>The scheme comes from {@code X-Forwarded-Proto} when there is one</b>, because a TLS
 * terminator in front of the edge is the only hop that knows the answer — the same reason {@code
 * EdgeHeaders} sets that header only when it is absent. The FIRST value is read: the header is a
 * list, oldest hop first, and the outermost hop is the one that faced the client.
 *
 * <p>Framework-free and static on purpose, next to {@link HostEnvironments} and for the same
 * reason: this is one of the two pieces of behaviour worth asserting without booting anything, and
 * {@code EnvironmentAuthorityTest} is where the edge cases live.
 *
 * @param scheme {@code http} or {@code https}, whichever faced the client
 * @param authority the innermost door's authority, port and all — never null, because every answer
 *     is an address a browser could be sent to
 * @param project the project that authority is inside, or null when the name named none. It is the
 *     one thing that decides whether an application name can be composed at all.
 */
public record EnvironmentAuthority(String scheme, String authority, String project) {

  /**
   * Where the innermost door is: {@code https://dev.acme.example.com} for an env-supporting
   * project, {@code https://qits.example.com} for an env-less one, and the canonical origin for a
   * name that names no project.
   *
   * <p>It is also what an application label is prefixed onto, which is the whole of {@code
   * projectOrigin}'s contract in the navigation document: {@code editor.} in front of this
   * authority is the editor's name for this project.
   */
  public String origin() {
    return scheme + "://" + authority;
  }

  /**
   * {@code https://ci.dev.acme.example.com} — where one application of this project is. One label,
   * always: the project label and the environment label are already in the authority.
   *
   * @return null when this name is inside no project, because there is then no application address
   *     to compose. A door answers without a redirect rather than with a name that 404s.
   */
  public String hostOrigin(String host) {
    return project == null ? null : scheme + "://" + host + "." + authority;
  }

  /**
   * @param host the request's {@code Host} or {@code :authority}, port and all; null is a request
   *     that carried neither
   * @param forwardedProto the {@code X-Forwarded-Proto} header, or null
   * @param requestScheme the scheme this process was reached over, when nothing forwarded one
   * @param hosts the reader that holds the grammar — the stated domain above all. The composition
   *     is the reading spelled forwards, so the two cannot be given different domains.
   * @param projects the projects that exist right now, slug to whether that project has
   *     environments — a per-call parameter for the same reason as {@link
   *     HostEnvironments#route(String, Map)}'s: it moves with the event stream.
   * @param canonicalAuthority {@code qits.edge.sessions.canonical-origin}'s authority, or null
   */
  public static EnvironmentAuthority of(
      String host,
      String forwardedProto,
      String requestScheme,
      HostEnvironments hosts,
      Map<String, Boolean> projects,
      String canonicalAuthority) {
    String scheme = scheme(forwardedProto, requestScheme);
    EnvironmentAuthority named = scoped(scheme, host, hosts, projects);
    if (named != null) {
      return named;
    }
    // The name named no project, so it can compose nothing of its own. The canonical origin is the
    // one name a deployment states about itself, and it is read by the same grammar: a deployment
    // whose origin is its project's door gives the apex a front door, and one whose origin is the
    // bare apex leaves this without a project — which is the honest answer, not a broken name.
    EnvironmentAuthority canonical = scoped(scheme, canonicalAuthority, hosts, projects);
    if (canonical != null) {
      return canonical;
    }
    String fallback = name(canonicalAuthority);
    return new EnvironmentAuthority(
        scheme, fallback.isEmpty() ? hosts.domain() : fallback + port(canonicalAuthority), null);
  }

  /**
   * One name composed back up from its own reading, or null when that reading named no project.
   *
   * <p>The environment label is present exactly when the project supports environments, which is
   * the same question {@link HostEnvironments} asked to READ the name — an env-less project's
   * applications sit directly inside it, in the default environment, and its door is the innermost
   * one there is.
   */
  private static EnvironmentAuthority scoped(
      String scheme, String host, HostEnvironments hosts, Map<String, Boolean> projects) {
    String name = name(host);
    if (name.isEmpty()) {
      return null;
    }
    HostEnvironments.Route route = hosts.route(name, projects);
    String project = route.project();
    if (project == null || !projects.containsKey(project)) {
      // The apex, an address, a name outside the domain, a name with too many labels — and a
      // project label naming a project this edge does not know, which is a 404 rather than a place.
      return null;
    }
    Boolean supportsEnvironments = projects.get(project);
    String inside =
        supportsEnvironments == null || supportsEnvironments ? route.environment() + "." : "";
    return new EnvironmentAuthority(
        scheme, inside + project + "." + hosts.domain() + port(host), project);
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

  /**
   * Lower case, no surrounding space, no trailing root dot, no port, no IPv6 brackets.
   *
   * <p>Package-visible because {@code EdgeRouter} reads the stated domain off the canonical origin
   * with it, and one spelling of the normalisation is the point.
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

  /** The {@code :8080} of a name, or an empty string when it carried none. */
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
}
