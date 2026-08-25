package eu.wohlben.qits.edge;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Browser sessions, terminated at the edge — the other half of {@link EdgeAuth}, for the caller
 * that has no client id and cannot be given one.
 *
 * <p><b>The cookie is opaque and idp is the truth.</b> {@code qits-session} carries 256 random
 * bits; idp stores their fingerprint against a row. So this process cannot decide anything about a
 * session on its own — it asks, and caches the answer. The alternative, a signed cookie verified
 * offline against the JWKS this process already holds, would cost revocation: a logout would be a
 * row idp changed and nobody read. What that choice costs instead is an idp round trip, which the
 * cache below pays for once per session per {@link SessionsConfig#cacheTtlMs()}.
 *
 * <h2>A gated request, in five steps</h2>
 *
 * <p>{@link EdgeRouter} runs them on the environment vhost, and only while {@link
 * SessionsConfig#enabled()}. Application vhosts are untouched by every line here — they front
 * services that no browser talks to, and their gate is {@link AuthConfig}'s.
 *
 * <ol>
 *   <li>Every inbound {@code X-Qits-*} header is dropped. See {@link EdgeHeaders#applyIdentity}.
 *   <li>A {@code Bearer} or {@code Basic} machine credential takes {@link EdgeAuth}'s path exactly,
 *       and is proxied with NO identity headers — a machine's identity is in its token, and writing
 *       a username for it would invent one.
 *   <li>A session cookie is introspected here and becomes the three identity headers.
 *   <li>A path under {@link SessionsConfig#anonymousPrefixes()} is proxied anonymously — the login
 *       page has to be reachable by someone who cannot log in yet.
 *   <li>Anything else is refused: a navigation is sent to the login page — which lives on the host
 *       of whichever deployment owns {@link SessionsConfig#loginPath()} — and everything else gets
 *       a 401. See {@link #refuse}.
 * </ol>
 *
 * <h2>The two things the cache is for</h2>
 *
 * <p><b>Not putting idp on every request's path</b>, which is the ordinary reason, and <b>outliving
 * an idp that is being replaced</b>, which is the reason that was learnt the hard way: the token
 * broker died inside an idp redeploy on 2026-08-14. A machine retries a push and nobody notices; a
 * person is logged out mid-click. So within {@link SessionsConfig#staleGraceMs()} of a cached
 * session's freshness running out, an idp that cannot be reached AT ALL leaves the belief standing.
 * An idp that answers "no" does not — that is a decision, and it is obeyed at once.
 *
 * <p>Refusals are never cached, for the same reason {@link EdgeAuth} does not cache one: the case
 * it would speed up is a caller whose session was just created, which would then keep being refused
 * after the login it just completed.
 */
@ApplicationScoped
public class EdgeSessions {

  private static final Logger LOG = Logger.getLogger(EdgeSessions.class);

  /** Not in Vert.x's constants, and only ever read — this process sets no cookie of its own. */
  private static final String COOKIE = "Cookie";

  /** The header browsers stamp on every request to say what kind of fetch it is. */
  static final String FETCH_MODE = "Sec-Fetch-Mode";

  @Inject SessionsConfig config;

  @Inject AuthConfig authConfig;

  @Inject Idp idp;

  @Inject Vertx vertx;

  private HttpClient client;

  /** {@code Basic <id>:<secret>} for the edge's own idp client, built once. */
  private String authorization;

  /** {@link SessionsConfig#anonymousPrefixes()} with blanks dropped, read once at startup. */
  private List<String> anonymousPrefixes;

  /**
   * The environment door. It is what every default-environment name is derived from, and the login
   * origin's fallback while no deployment has published a host for the login path's owner.
   */
  private URI canonicalOrigin;

  private Set<String> browserHosts;

  /** The {@code *.<authority>} entries, each stored as the authority behind the wildcard. */
  private List<String> wildcardBrowserHosts;

  /** Cookie fingerprint to what idp said about it. Bounded and least-recently-used. */
  private Map<String, Cached> sessions;

  /**
   * A session idp vouched for.
   *
   * @param userId the subject, into {@code X-Qits-User-Id}
   * @param username the name an upstream writes into an audit column, into {@code X-Qits-User}
   * @param roles the role strings comma-separated, into {@code X-Qits-Roles} — safe because a role
   *     never holds a comma, and one that did is dropped rather than allowed to split into two
   * @param expiresAtMillis when the session dies whatever any cache believes
   */
  public record Session(String userId, String username, String roles, long expiresAtMillis) {}

  /** What idp answered, whatever its status — an unreachable idp is a failed future instead. */
  private record Answer(int status, String body) {}

  /** A believed session and the moment that belief needs renewing. */
  private record Cached(Session session, long freshUntilMillis) {}

  @PostConstruct
  void open() {
    // Its own client, like IdpGrants': the proxy's is tuned for 64 concurrent layer pushes with no
    // idle timeout, which is the opposite of a small JSON POST that must fail fast and be retried.
    client = vertx.createHttpClient();
    authorization =
        config.clientId().isPresent() && config.clientSecret().isPresent()
            ? "Basic "
                + Base64.getEncoder()
                    .encodeToString(
                        (config.clientId().get() + ":" + config.clientSecret().get())
                            .getBytes(StandardCharsets.UTF_8))
            : null;
    anonymousPrefixes = prefixes(config.anonymousPrefixes());
    canonicalOrigin = parseOrigin(config.canonicalOrigin());
    browserHosts = browserHosts(config.browserHosts());
    wildcardBrowserHosts = wildcardBrowserHosts(config.browserHosts());
    String canonicalAuthority = authority(canonicalOrigin.getAuthority());
    if ((browserHosts.isEmpty() && wildcardBrowserHosts.isEmpty())
        || canonicalAuthority == null
        || !browserHost(canonicalAuthority, browserHosts, wildcardBrowserHosts)) {
      throw new IllegalStateException(
          "qits.edge.sessions.browser-hosts must include qits.edge.sessions.canonical-origin");
    }
    int capacity = config.cacheSize();
    sessions =
        Collections.synchronizedMap(
            // Access-ordered, so the entry evicted is the one longest unused rather than the one
            // written longest ago — an open tab stays cached while a one-off caller ages out.
            new LinkedHashMap<>(16, 0.75f, true) {
              @Override
              protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                return size() > capacity;
              }
            });
  }

  /**
   * A gate with no credential of its own could never open, so this fails the process at STARTUP
   * rather than per request — the same rule as the environment list and the default environment.
   * The failure would otherwise be every browser refused, with the reason only in a stack trace.
   */
  void requireItsOwnCredential(@Observes StartupEvent ignored) {
    if (config.enabled() && authorization == null) {
      throw new IllegalStateException(
          "qits.edge.sessions.enabled is on, but the edge has no idp client to introspect with."
              + " Set QITS_EDGE_SESSIONS_CLIENT_ID and QITS_EDGE_SESSIONS_CLIENT_SECRET (the"
              + " {env}-qits-edge client the bootstrap seeds), or turn the gate off.");
    }
    if (config.enabled()) {
      LOG.infof(
          "browser sessions are gated here: cookie %s, login %s on the host that owns it (%s while"
              + " none does), browser hosts %s %s, anonymous %s",
          config.cookieName(),
          config.loginPath(),
          canonicalOrigin,
          browserHosts,
          wildcardBrowserHosts.stream().map(suffix -> "*." + suffix).toList(),
          anonymousPrefixes);
    }
  }

  /** Whether this process gates browsers at all. Everything else here is dead while it is false. */
  public boolean enabled() {
    return config.enabled();
  }

  /**
   * The configured canonical browser authority — {@code wohlben.eu}, {@code localhost:8080}. It is
   * what an environment's names are built from when a request's own Host says which environment it
   * is not; see {@link EnvironmentAuthority}.
   */
  public String canonicalAuthority() {
    return authority(canonicalOrigin.getAuthority());
  }

  /**
   * Where the login page is served, below whichever host owns that route. A contract with
   * qits-platform-idp's SPA rather than a deployment's choice.
   */
  public String loginPath() {
    return config.loginPath();
  }

  /** The one browser credential machine vhosts must remove before proxying. */
  public String cookieName() {
    return config.cookieName();
  }

  /** The session cookie this request carries, or null when it carries none. */
  public String cookie(HttpServerRequest request) {
    return cookieValue(request.getHeader(COOKIE), config.cookieName());
  }

  /**
   * One cookie out of a {@code Cookie} header.
   *
   * <p>Parsed rather than read through Vert.x's own accessor so it can be asserted without a
   * request, and because the shapes that matter are the ones a browser sends: several pairs, spaces
   * after the semicolons, and a value a server chose to quote. Cookie NAMES are case-sensitive.
   */
  static String cookieValue(String header, String name) {
    if (header == null || name == null) {
      return null;
    }
    for (String pair : header.split(";")) {
      int equals = pair.indexOf('=');
      if (equals <= 0 || !pair.substring(0, equals).strip().equals(name)) {
        continue;
      }
      String value = pair.substring(equals + 1).strip();
      if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
        value = value.substring(1, value.length() - 1);
      }
      return value.isEmpty() ? null : value;
    }
    return null;
  }

  /** Whether this path is served to anyone — see {@link SessionsConfig#anonymousPrefixes()}. */
  public boolean anonymous(String path) {
    return anonymous(path, anonymousPrefixes);
  }

  /** Package-private and static so the prefix rule can be asserted without booting anything. */
  static boolean anonymous(String path, List<String> prefixes) {
    if (path == null) {
      return false;
    }
    for (String prefix : prefixes) {
      if (path.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /** The configured prefixes, stripped, with blanks dropped. */
  static List<String> prefixes(List<String> configured) {
    List<String> read = new ArrayList<>();
    for (String prefix : configured) {
      if (prefix != null && !prefix.isBlank()) {
        read.add(prefix.strip());
      }
    }
    return List.copyOf(read);
  }

  /**
   * What idp says about a cookie: cached belief first, then a call.
   *
   * @return a future holding the session, or holding NULL when idp refused it — an unknown, expired
   *     or revoked session. A FAILED future means idp could not be reached and no cached belief was
   *     close enough to stand in; the caller refuses on it, but nothing is written down about the
   *     cookie, because nothing was learnt about it.
   */
  public Future<Session> introspect(String cookie) {
    String fingerprint = EdgeAuth.fingerprint(cookie);
    long now = System.currentTimeMillis();
    Cached known = sessions.get(fingerprint);
    if (known != null && known.freshUntilMillis() > now && live(known.session(), now)) {
      return Future.succeededFuture(known.session());
    }
    return attempt(cookie, now + authConfig.idpRetryWindowMs(), 0)
        .map(
            answer -> {
              if (answer.status() != 200) {
                // idp DECIDED. A logout, a revocation, a session that ran out — the belief goes,
                // and it goes now rather than at the end of the cache's own window.
                sessions.remove(fingerprint);
                return null;
              }
              Session session = read(answer.body());
              if (session == null) {
                sessions.remove(fingerprint);
                return null;
              }
              sessions.put(
                  fingerprint,
                  new Cached(session, System.currentTimeMillis() + config.cacheTtlMs()));
              return session;
            })
        .recover(
            failure -> {
              // NOT an answer: idp is unreachable, which is what a redeploying container looks
              // like.
              Cached grace = sessions.get(fingerprint);
              long moment = System.currentTimeMillis();
              if (grace != null
                  && moment < grace.freshUntilMillis() + config.staleGraceMs()
                  && live(grace.session(), moment)) {
                LOG.warnf(
                    "%s is unreachable (%s); a session cached here answers for up to %dms more",
                    idp.introspectionEndpoint(), failure.toString(), config.staleGraceMs());
                return Future.succeededFuture(grace.session());
              }
              return Future.failedFuture(failure);
            });
  }

  /** A session's own expiry is honoured whatever a cache believes — the grace never widens it. */
  private static boolean live(Session session, long nowMillis) {
    return session.expiresAtMillis() > nowMillis;
  }

  /**
   * The refusal, in the shape the caller can act on.
   *
   * <p><b>A navigation is redirected and everything else is not</b>, which is the same distinction
   * qits-gateway's {@code NonNavigationRequestChecker} makes and for the same reason: only a
   * request that renders a document can show a login page. A 302 handed to an {@code EventSource}
   * or a {@code fetch} is followed into HTML the caller cannot use, and handed to a WebSocket
   * handshake it kills the socket with nothing to read.
   *
   * <p><b>No {@code WWW-Authenticate} here</b>, unlike {@link EdgeAuth#challenge}. A {@code Basic}
   * challenge would pop the browser's own credential dialog on every background fetch a logged-out
   * tab makes, and the credential a browser holds is a cookie. The body names the login page so an
   * SPA can send the user there itself.
   *
   * @param loginOrigin the origin the login page is served from, or null for the canonical one —
   *     the caller reads it off the projection, because the page lives on the host of whichever
   *     deployment owns {@link #loginPath()}
   */
  public void refuse(HttpServerRequest request, String loginOrigin) {
    String location =
        loginLocation(
            loginOrigin,
            request.authority() == null ? null : request.authority().toString(),
            request.uri());
    if (isNavigation(
        request.method(), request.getHeader(FETCH_MODE), request.getHeader(HttpHeaders.ACCEPT))) {
      request
          .response()
          .setStatusCode(302)
          .putHeader(HttpHeaders.LOCATION, location)
          // A cached redirect would keep sending a logged-in browser to the login page.
          .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
          .end();
      return;
    }
    request
        .response()
        .setStatusCode(401)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        .end(
            new JsonObject()
                .put("error", "authentication required")
                .put("login", location)
                .encode());
  }

  /**
   * Whether this request could render a login page.
   *
   * <p>{@code Sec-Fetch-Mode} is the answer when it is there: every current browser stamps it, and
   * {@code navigate} is the one value that means "a document is being loaded". Nothing else does —
   * {@code cors}, {@code no-cors} and {@code websocket} are all requests made by a page that
   * already exists. A caller that sends none at all (curl, an old client) is read from the method
   * and {@code Accept}, which is the shape a real navigation had before the header existed.
   *
   * <p>Package-private and static so the matrix can be asserted without a socket.
   */
  static boolean isNavigation(HttpMethod method, String fetchMode, String accept) {
    if (fetchMode != null) {
      return fetchMode.strip().equalsIgnoreCase("navigate");
    }
    return method == HttpMethod.GET
        && accept != null
        && accept.toLowerCase(Locale.ROOT).contains("text/html");
  }

  /**
   * The login page with a configured return host and the request path to come back to.
   *
   * <p>The ORIGIN is the caller's, because the page moves with its deployment. Only the return host
   * is decided here: an authority nobody listed falls back to the door rather than being reflected.
   */
  String loginLocation(String loginOrigin, String requestedAuthority, String uri) {
    String host = authority(requestedAuthority);
    if (host == null || !browserHost(host, browserHosts, wildcardBrowserHosts)) {
      host = authority(canonicalOrigin.getAuthority());
    }
    return (loginOrigin == null ? canonicalOrigin.toString() : loginOrigin)
        + config.loginPath()
        + "?return_host="
        + URLEncoder.encode(host, StandardCharsets.UTF_8)
        + "&return_path="
        + URLEncoder.encode(redirectTarget(uri), StandardCharsets.UTF_8);
  }

  private static URI parseOrigin(String configured) {
    URI origin = URI.create(configured.strip());
    if (!("http".equals(origin.getScheme()) || "https".equals(origin.getScheme()))
        || origin.getHost() == null
        || origin.getRawQuery() != null
        || origin.getRawFragment() != null
        || !"".equals(origin.getPath())) {
      throw new IllegalStateException(
          "qits.edge.sessions.canonical-origin must be an http(s) origin with no path, query, or fragment");
    }
    return origin;
  }

  /** The exact authorities, which is what most entries are. */
  static Set<String> browserHosts(List<String> configured) {
    java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<>();
    for (String value : configured) {
      if (wildcard(value) != null) {
        continue;
      }
      String authority = authority(value);
      if (authority != null) {
        hosts.add(authority);
      }
    }
    return Set.copyOf(hosts);
  }

  /**
   * The {@code *.<authority>} entries, each reduced to the authority behind the wildcard.
   *
   * <p>ONE extra label, never a suffix match. {@code *.dev.example.com} is what makes every service
   * of one environment a browser host without listing them — the names are {@code
   * <app>.dev.example.com} and the app list is the deployment's, not this file's. A suffix check
   * would also accept {@code evil.co.dev.example.com}, which is a different site to a browser and a
   * return target this process must not accept.
   */
  static List<String> wildcardBrowserHosts(List<String> configured) {
    List<String> suffixes = new ArrayList<>();
    for (String value : configured) {
      String authority = wildcard(value);
      if (authority != null) {
        suffixes.add(authority);
      }
    }
    return List.copyOf(suffixes);
  }

  /** The authority behind a {@code *.} entry, or null when this entry is not one. */
  private static String wildcard(String configured) {
    if (configured == null || !configured.strip().startsWith("*.")) {
      return null;
    }
    return authority(configured.strip().substring(2));
  }

  /**
   * Whether this authority may receive a person after login: an exact entry, or exactly one label
   * in front of a wildcard entry's authority. The port is part of the authority on both sides, so a
   * name on another port matches nothing.
   *
   * <p>Package-private and static so the matrix can be asserted without booting anything.
   */
  static boolean browserHost(String host, Set<String> exact, List<String> wildcards) {
    if (host == null) {
      return false;
    }
    if (exact.contains(host)) {
      return true;
    }
    for (String suffix : wildcards) {
      if (!host.endsWith("." + suffix)) {
        continue;
      }
      String label = host.substring(0, host.length() - suffix.length() - 1);
      if (!label.isEmpty() && label.indexOf('.') < 0) {
        return true;
      }
    }
    return false;
  }

  /** A lower-case host plus optional port, never a URL, path, user-info, or wildcard. */
  static String authority(String raw) {
    if (raw == null || raw.isBlank() || raw.indexOf('/') >= 0 || raw.indexOf('\\') >= 0) {
      return null;
    }
    try {
      URI parsed = URI.create("https://" + raw.strip());
      if (parsed.getHost() == null
          || parsed.getUserInfo() != null
          || parsed.getPath().length() != 0
          || parsed.getRawQuery() != null
          || parsed.getRawFragment() != null) {
        return null;
      }
      String host = parsed.getHost().toLowerCase(Locale.ROOT);
      return parsed.getPort() < 0 ? host : host + ":" + parsed.getPort();
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  /**
   * The path a login may return to: this request's own, or {@code /} when it cannot be one.
   *
   * <p><b>This is an open-redirect guard and it is the reason the method exists.</b> The value is
   * handed to the login page, which will send the browser there after authenticating — so anything
   * that can name another origin turns the platform's own login into a redirector for somebody
   * else's. Same-origin means: it starts with exactly one slash. {@code //evil.example.com} is a
   * protocol-relative URL, {@code /\evil.example.com} is the same thing to every browser's parser
   * (backslash and slash are interchangeable there, whatever the RFC says), and an absolute URL
   * names its own host outright.
   *
   * <p>Control characters go too: the value is written into a {@code Location} header, and a
   * carriage return in a header value is a second header.
   */
  static String redirectTarget(String uri) {
    if (uri == null || uri.isBlank()) {
      return "/";
    }
    for (int i = 0; i < uri.length(); i++) {
      char c = uri.charAt(i);
      if (c < 0x20 || c == 0x7f) {
        return "/";
      }
    }
    String parsed = uri.replace('\\', '/');
    if (!parsed.startsWith("/") || parsed.startsWith("//")) {
      return "/";
    }
    return uri;
  }

  /**
   * idp's answer to a usable session, or null when it is not one.
   *
   * <p>A malformed answer is a refusal rather than a failure: something replied, so waiting for it
   * again would not help, and believing half of it would inject an identity idp did not assert.
   */
  static Session read(String body) {
    JsonObject answered;
    try {
      answered = new JsonObject(body);
    } catch (RuntimeException notJson) {
      LOG.warn("the identity provider's introspection answer was not JSON");
      return null;
    }
    String userId = answered.getString("userId");
    String username = answered.getString("username");
    if (!headerSafe(userId) || !headerSafe(username)) {
      // Both go into headers an upstream trusts unconditionally, and a control character in one of
      // them is a second header. Nothing about the platform's own idp sends such a value; refusing
      // rather than sanitising means a strange answer never becomes a strange identity.
      LOG.warn("the identity provider named no usable user");
      return null;
    }
    return new Session(
        userId, username, rolesHeader(answered.getJsonArray("roles")), expiry(answered));
  }

  /**
   * {@code expiresAt} as epoch milliseconds. An ISO-8601 instant, which is what the platform's
   * services put on the wire; a missing or unreadable one is treated as no deadline of its own,
   * because idp has just said the session is good and {@link SessionsConfig#cacheTtlMs()} bounds
   * how long that is believed anyway.
   */
  private static long expiry(JsonObject answered) {
    String expiresAt = answered.getString("expiresAt");
    if (expiresAt == null || expiresAt.isBlank()) {
      return Long.MAX_VALUE;
    }
    try {
      return Instant.parse(expiresAt.strip()).toEpochMilli();
    } catch (DateTimeParseException notAnInstant) {
      LOG.warnf("the identity provider dated a session `%s`, which is not an instant", expiresAt);
      return Long.MAX_VALUE;
    }
  }

  /**
   * The role set as one header value. Comma-separated, which is safe because a role is {@code
   * $app:$resource:$role} and holds no comma — and one that somehow did is DROPPED rather than
   * allowed to arrive downstream as two roles.
   */
  static String rolesHeader(JsonArray roles) {
    if (roles == null) {
      return "";
    }
    StringBuilder joined = new StringBuilder();
    for (int i = 0; i < roles.size(); i++) {
      Object value = roles.getValue(i);
      if (!(value instanceof String role)) {
        continue;
      }
      String read = role.strip();
      if (read.isEmpty() || read.indexOf(',') >= 0 || !headerSafe(read)) {
        continue;
      }
      if (!joined.isEmpty()) {
        joined.append(',');
      }
      joined.append(read);
    }
    return joined.toString();
  }

  /** Whether a value can be written into a header at all: present, and no control characters. */
  static boolean headerSafe(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 || c == 0x7f) {
        return false;
      }
    }
    return true;
  }

  /**
   * The introspection call, with {@link IdpGrants}' patience — bounded per attempt, retried while
   * the failure is the network and the window has time left. An ANSWER is never retried: idp saying
   * no about a session is idp deciding, and asking again would turn one refusal into a burst.
   */
  private Future<Answer> attempt(String cookie, long deadlineMillis, int made) {
    return post(cookie)
        .recover(
            failure -> {
              long backoff = IdpGrants.backoffMs(made);
              if (!IdpGrants.connectionClassed(failure)
                  || System.currentTimeMillis() + backoff >= deadlineMillis) {
                return Future.failedFuture(failure);
              }
              Promise<Answer> next = Promise.promise();
              vertx.setTimer(
                  backoff, id -> attempt(cookie, deadlineMillis, made + 1).onComplete(next));
              return next.future();
            });
  }

  private Future<Answer> post(String cookie) {
    RequestOptions options =
        new RequestOptions()
            .setMethod(HttpMethod.POST)
            .setAbsoluteURI(idp.introspectionEndpoint())
            // Both halves, the same as every other dial at idp: the connect timeout bounds a
            // dropped
            // SYN — a swarm VIP exists before any task behind it does — and the request timeout
            // bounds the worse case, a connection accepted and never answered.
            .setConnectTimeout(authConfig.idpCallTimeoutMs())
            .setTimeout(authConfig.idpCallTimeoutMs());
    return client
        .request(options)
        .compose(
            request -> {
              // The edge's own client id and secret, which is what makes introspection a privilege
              // rather than an oracle anyone on the network could ask about any cookie.
              request.putHeader(HttpHeaders.AUTHORIZATION, authorization);
              request.putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
              request.putHeader(HttpHeaders.ACCEPT, "application/json");
              return request.send(new JsonObject().put("token", cookie).encode());
            })
        .compose(
            response ->
                response.body().map(body -> new Answer(response.statusCode(), body.toString())));
  }
}
