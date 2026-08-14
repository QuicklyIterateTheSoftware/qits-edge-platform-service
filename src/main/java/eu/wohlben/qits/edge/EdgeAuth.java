package eu.wohlben.qits.edge;

import io.vertx.core.Future;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * idp authentication, terminated at the edge — the first node, which is the whole point.
 *
 * <p>The edge sees every request before anything else does and already reads the Host header, so it
 * is the cheapest place to gate. What it gates is a <b>vhost</b>, never a path: an {@code
 * $app.$env.$domain} name fronts a service with no external auth of its own, so the name is the
 * decision and {@link AuthConfig} is the switch.
 *
 * <h2>The docker half</h2>
 *
 * <p>{@code docker login} stores a password and resends it forever, while an idp token lives ~300
 * seconds and cannot be refreshed. The Distribution spec's own answer is the <b>Bearer token
 * endpoint</b> flow, and it is the one this implements:
 *
 * <ol>
 *   <li>docker asks for something and gets {@link #challenge a 401} naming a {@code realm};
 *   <li>docker GETs that realm with HTTP Basic — the idp CLIENT ID and CLIENT SECRET a user stored
 *       with {@code docker login}, which is the durable credential;
 *   <li>{@link #token} brokers a {@code client_credentials} grant to idp over qits-net and hands
 *       back a short-lived token, docker-style;
 *   <li>docker retries with {@code Authorization: Bearer …}, and re-fetches when it expires.
 * </ol>
 *
 * <p>The token itself is validated OFFLINE against idp's published keys ({@link IdpKeys}), so idp
 * is on the login path and not on the per-pull path.
 *
 * <h2>The other half: clients that cannot do the dance</h2>
 *
 * <p>maven, npm and git send HTTP Basic and nothing else — there is no client in any of them that
 * reads a {@code WWW-Authenticate: Bearer} challenge, fetches a token and retries. So a gated
 * request carrying {@code Authorization: Basic} is validated the only way a client id and secret
 * can be: by spending them at idp ({@link IdpGrants}) and reading the token that comes back. What
 * happens next is the Bearer path exactly — same issuer, same expiry, same signature, same demanded
 * audience — so a commissioned client opens precisely the vhosts its audiences name and no others.
 *
 * <p><b>The result is cached against a HASH of the credential</b>, never the credential, for the
 * shorter of the minted token's life and {@link AuthConfig#basicCacheTtlMs()}. Without it every
 * dependency fetch would put an idp round trip on the path, which is the thing offline validation
 * exists to avoid. Refusals are not cached — see {@link #checkBasic}.
 *
 * <h2>The one gap in a gated vhost</h2>
 *
 * <p>A vhost is the decision, but not every METHOD on it has to be. {@link
 * AuthConfig#anonymousReadApps()} names app labels whose {@code GET} and {@code HEAD} pass without
 * a credential, because reads are the bootstrap steps — pulling a base image, cloning, fetching a
 * dependency — that happen before there is anything to hold a token. Writes on the same name keep
 * the whole check, so the exemption cannot widen into "this service is public".
 *
 * <p>docker's {@code service} and {@code scope} query parameters are read and dropped. The
 * permission is the audience the token already carries; per-repository grants would be a change to
 * the platform's claim model, not to this class.
 */
@ApplicationScoped
public class EdgeAuth {

  private static final Logger LOG = Logger.getLogger(EdgeAuth.class);

  /**
   * The token endpoint's path on an application vhost. Fixed rather than configured: it is baked
   * into every challenge this process sends, so a client never has to be told it, and the value
   * only has to avoid colliding with the fronted services' own paths (the registry answers under
   * {@code /v2}, the git host under {@code /git} and {@code /githost}).
   */
  public static final String TOKEN_PATH = "/token";

  /** Not in Vert.x's HttpHeaders constants, so it is spelled once here. */
  private static final String WWW_AUTHENTICATE = "WWW-Authenticate";

  private static final String BEARER = "bearer ";
  private static final String BASIC = "basic ";

  @Inject AuthConfig config;

  @Inject Idp idp;

  @Inject IdpKeys keys;

  @Inject IdpGrants grants;

  /** {@link AuthConfig#anonymousReadApps()}, normalised once — see {@link #readApps}. */
  private Set<String> anonymousReadApps;

  /**
   * Credential fingerprint to what idp said about it. Bounded and least-recently-used: the key
   * comes from a caller, so an unbounded map is a caller-sized allocation.
   */
  private Map<String, Validated> validated;

  @PostConstruct
  void open() {
    anonymousReadApps = readApps(config.anonymousReadApps().orElse(List.of()));
    int capacity = config.basicCacheSize();
    validated =
        Collections.synchronizedMap(
            // Access-ordered, so the entry evicted is the one longest unused rather than the one
            // written longest ago — a busy client stays cached while a one-off caller ages out.
            new LinkedHashMap<>(16, 0.75f, true) {
              @Override
              protected boolean removeEldestEntry(Map.Entry<String, Validated> eldest) {
                return size() > capacity;
              }
            });
  }

  /**
   * A credential idp accepted: the audiences the token it minted carried, and when this belief
   * stops. The audiences are kept rather than a yes/no, because the demanded audience is a
   * per-request question — one cached validation must still refuse the vhost of another tier.
   */
  private record Validated(JsonArray audiences, long expiresAtMillis) {}

  /**
   * The configured app labels, in the spelling {@link HostEnvironments} produces: stripped, lower
   * case, blanks dropped. A Host name arrives in any case at all, so matching without this would
   * make {@code Registry.dev.example.com} gated and {@code registry.dev.example.com} open.
   */
  static Set<String> readApps(List<String> configured) {
    Set<String> names = new LinkedHashSet<>();
    for (String app : configured) {
      if (app != null && !app.isBlank()) {
        names.add(app.strip().toLowerCase(Locale.ROOT));
      }
    }
    return Set.copyOf(names);
  }

  /**
   * Whether this request is a read the deployment opened: a {@code GET} or a {@code HEAD}, on an
   * APP vhost, whose app label was named.
   *
   * <p>All three conditions are load-bearing. {@code toApp()} keeps the exemption off the
   * environment vhost, which routes the platform's whole existing traffic and has its own switch.
   * The app label is the one the routing decision already resolved, so a label the edge does not
   * route never reaches here — an unknown app is answered 404 one step earlier. And the method list
   * is the two that read: everything that changes the service still needs a token.
   *
   * <p>Package-private and static so it can be asserted without booting an application.
   */
  static boolean anonymousRead(
      HostEnvironments.Route route, HttpMethod method, Set<String> readApps) {
    return route.toApp()
        && (method == HttpMethod.GET || method == HttpMethod.HEAD)
        && readApps.contains(route.app());
  }

  /**
   * The audience this vhost demands: the configured pattern with {@code {env}} filled in from the
   * environment the Host name named.
   *
   * <p><b>This is the boundary between tiers.</b> idp's audience values are env-prefixed, so
   * deriving the demand per request is what stops a token minted for dev's registry from opening
   * prod's vhost — one entry, and neither tier can unlock the other. A pattern with no placeholder
   * comes back unchanged, which is a literal audience and is what a single-audience deployment
   * wants.
   */
  static String audienceFor(String pattern, String environment) {
    return pattern.replace("{env}", environment);
  }

  /** Whether this request is docker fetching a token rather than asking for a registry object. */
  public static boolean isTokenRequest(HttpServerRequest request) {
    return TOKEN_PATH.equals(request.path())
        && (request.method() == HttpMethod.GET || request.method() == HttpMethod.POST);
  }

  /**
   * Whether this request may proceed.
   *
   * @return a future holding null when it may, or the reason it may not. A failed future means the
   *     check could not be made — the caller denies on that too, so that an idp outage cannot
   *     become an open door.
   */
  public Future<String> check(HostEnvironments.Route route, HttpServerRequest request) {
    boolean enforce = route.toApp() ? config.enforceOnApps() : config.enforceOnEnvironments();
    if (!enforce || anonymousRead(route, request.method(), anonymousReadApps)) {
      return Future.succeededFuture(null);
    }
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    String audience = audienceFor(config.audiencePattern(), route.environment());
    if (header != null && header.toLowerCase(Locale.ROOT).startsWith(BASIC)) {
      // A client id and secret, sent by something that cannot do docker's token dance — maven, npm,
      // git. Spending them at idp is the only way to know they are good.
      return checkBasic(header.substring(BASIC.length()).trim(), audience);
    }
    if (header == null || !header.toLowerCase(Locale.ROOT).startsWith(BEARER)) {
      return Future.succeededFuture("no bearer token");
    }
    SignedJwt jwt;
    try {
      jwt = SignedJwt.parse(header.substring(BEARER.length()).trim());
    } catch (IllegalArgumentException e) {
      return Future.succeededFuture(e.getMessage());
    }
    String problem = jwt.problem(idp.issuer(), audience, Instant.now(), config.clockSkewSeconds());
    if (problem != null) {
      // Claims before signature: a claim check needs no key, so an expired or misaddressed token is
      // refused without a JWKS lookup — and a made-up kid cannot use one to force a fetch.
      return Future.succeededFuture(problem);
    }
    return keys.find(jwt.kid())
        .map(key -> jwt.signatureMatches(key) ? null : "the token's signature does not verify");
  }

  /**
   * Whether an HTTP Basic credential opens this vhost: cached belief first, then idp.
   *
   * <p><b>Only the acceptance is cached.</b> A refusal is not, and briefly caching one would be
   * worse than useless: the case it would speed up is a client whose secret was just rotated, which
   * would then keep being refused after the operator fixed it — a stuck door with no way to knock.
   * The cost of not caching is one idp call per wrong credential, which is idp's rate limit to
   * enforce and not a decision this process can make on its behalf.
   *
   * <p>An idp that cannot be reached at all is a FAILED future, never a refusal: the caller denies
   * on it (a validator that cannot answer must not open the door) but it is not written down as a
   * verdict about the credential.
   */
  private Future<String> checkBasic(String credential, String audience) {
    if (!isClientCredentials(credential)) {
      // Refused HERE, without a call. A credential that cannot be a client id and a secret has
      // nothing to ask idp about, and asking would spend the whole patience window on it during an
      // idp outage — which is how a client with no credential at all comes to hang.
      return Future.succeededFuture("the credential is not a client id and a secret");
    }
    String fingerprint = fingerprint(credential);
    Validated known = validated.get(fingerprint);
    if (known != null && known.expiresAtMillis() > System.currentTimeMillis()) {
      return Future.succeededFuture(refusalFor(known.audiences(), audience));
    }
    return grants
        .grant("Basic " + credential)
        .compose(
            grant -> {
              if (grant.status() != 200) {
                return Future.succeededFuture("the identity provider refused these credentials");
              }
              SignedJwt minted;
              try {
                minted = SignedJwt.parse(new JsonObject(grant.body()).getString("access_token"));
              } catch (RuntimeException e) {
                return Future.succeededFuture("the identity provider issued no usable token");
              }
              String problem =
                  minted.problem(idp.issuer(), Instant.now(), config.clockSkewSeconds());
              if (problem != null) {
                return Future.succeededFuture(problem);
              }
              // The same signature check a presented Bearer gets. The key is already cached, so it
              // is arithmetic — and running the one code path means a credential can never buy
              // more than the token it stands for.
              return keys.find(minted.kid())
                  .map(
                      key -> {
                        if (!minted.signatureMatches(key)) {
                          return "the minted token's signature does not verify";
                        }
                        validated.put(fingerprint, remember(minted));
                        return refusalFor(minted.audiences(), audience);
                      });
            });
  }

  /** null when these audiences include the one this vhost demands, the reason when they do not. */
  private static String refusalFor(JsonArray audiences, String audience) {
    return audiences.contains(audience) ? null : "the credential is not for " + audience;
  }

  /** How long to believe a credential: the token's own life, capped by configuration. */
  private Validated remember(SignedJwt minted) {
    long now = System.currentTimeMillis();
    long tokenLifeMs = minted.expiry() == null ? 0 : minted.expiry().toEpochMilli() - now;
    return new Validated(
        minted.audiences(), now + Math.max(0, Math.min(config.basicCacheTtlMs(), tokenLifeMs)));
  }

  /**
   * Whether this is base64 of {@code <client id>:<secret>} — RFC 7617's shape and nothing about
   * whether idp knows it.
   *
   * <p>The credential is decoded here and NOWHERE else: this process does not log it, store it or
   * carry it past this call, and what it relays to idp is the header exactly as it arrived.
   */
  static boolean isClientCredentials(String credential) {
    if (credential == null || credential.isBlank()) {
      return false;
    }
    String decoded;
    try {
      decoded = new String(Base64.getDecoder().decode(credential), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException notBase64) {
      return false;
    }
    int colon = decoded.indexOf(':');
    return colon > 0 && colon < decoded.length() - 1;
  }

  /**
   * A credential as a cache key. SHA-256, so the secret itself is never a map key, a log line or
   * anything a heap dump could hand over.
   */
  static String fingerprint(String credential) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              MessageDigest.getInstance("SHA-256")
                  .digest(credential.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not usable in this JVM", e);
    }
  }

  /**
   * The 401 an unauthenticated caller gets: the Distribution spec's error envelope, and the {@code
   * WWW-Authenticate} header the docker CLI reads to find its token endpoint.
   */
  public void challenge(HttpServerRequest request, String reason) {
    String authority = authority(request);
    LOG.debugf("401 on %s%s: %s", authority, request.path(), reason);
    request
        .response()
        .setStatusCode(401)
        .putHeader(WWW_AUTHENTICATE, bearerChallenge(scheme(request), authority, reason))
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .end(dockerErrors("UNAUTHORIZED", "authentication required").encode());
  }

  /**
   * The challenge value. {@code realm} is an absolute URL back to this same vhost's {@link
   * #TOKEN_PATH}, which is what makes the flow self-describing — docker is never configured with a
   * token endpoint, it is told one.
   *
   * <p>Package-private and static so {@code EdgeAuthTest} can pin the exact string; a challenge
   * docker does not parse is a challenge that fails with no message anywhere.
   */
  static String bearerChallenge(String scheme, String authority, String reason) {
    String challenge =
        "Bearer realm=\""
            + scheme
            + "://"
            + authority
            + TOKEN_PATH
            + "\",service=\""
            + authority
            + "\"";
    // `error` tells docker the credential it already has is dead, so it re-fetches rather than
    // giving up. Only when there WAS one: an error on a first anonymous request confuses clients.
    return "no bearer token".equals(reason) ? challenge : challenge + ",error=\"invalid_token\"";
  }

  /**
   * The token endpoint: docker's GET, HTTP Basic in, a docker-shaped token out.
   *
   * <p>What arrives is an idp client id and secret. They are relayed to idp's own token endpoint
   * verbatim — this process stores nothing and decides nothing about them beyond their SHAPE; idp
   * authenticates the client and decides its audiences, exactly as it does for every other machine
   * caller on the platform.
   *
   * <p><b>Every arm of this method ends a response.</b> That is the whole requirement docker places
   * on it: the CLI reaches this endpoint from a challenge it was handed and has no timeout of its
   * own, so an arm that answers nothing is a client that waits forever rather than one that fails.
   * A credential that is missing or is not a credential is answered here, without a call; a call is
   * bounded by {@link AuthConfig#idpCallTimeoutMs()} inside {@link AuthConfig#idpRetryWindowMs()};
   * and both outcomes of that end a response.
   */
  public void token(HttpServerRequest request) {
    String basic = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (basic == null || !basic.toLowerCase(Locale.ROOT).startsWith(BASIC)) {
      // Basic, not Bearer: this is the endpoint that SELLS bearer tokens, so asking for one here
      // would be a loop. docker sends the stored `docker login` credential when it sees this.
      basicChallenge(request, "client credentials required");
      return;
    }
    String credential = basic.substring(BASIC.length()).trim();
    if (!isClientCredentials(credential)) {
      // A header that says Basic and carries no client id and secret — an empty credential store,
      // a truncated helper answer. There is nothing to ask idp, and asking would hold the client
      // for the whole patience window while an unreachable idp is waited out.
      basicChallenge(request, "client credentials required");
      return;
    }
    LOG.debugf(
        "token request for service=%s scope=%s",
        request.getParam("service"), request.getParam("scope"));

    grants
        .grant(basic)
        .onSuccess(answer -> relay(request, answer))
        .onFailure(
            failure ->
                request
                    .response()
                    .setStatusCode(502)
                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                    .end(
                        dockerErrors("UNAVAILABLE", "the identity provider could not be reached")
                            .encode()));
  }

  /** The 401 that asks for the stored {@code docker login} credential. */
  private void basicChallenge(HttpServerRequest request, String message) {
    request
        .response()
        .setStatusCode(401)
        .putHeader(WWW_AUTHENTICATE, "Basic realm=\"" + authority(request) + "\"")
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        .end(dockerErrors("UNAUTHORIZED", message).encode());
  }

  /** idp's RFC 6749 token response, redressed as the Distribution spec's. */
  private void relay(HttpServerRequest request, IdpGrants.Grant answer) {
    if (answer.status() != 200) {
      LOG.warnf("idp refused a token request with %d", answer.status());
      request
          .response()
          .setStatusCode(401)
          .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
          .end(
              dockerErrors("UNAUTHORIZED", "the identity provider refused these credentials")
                  .encode());
      return;
    }
    JsonObject issued = new JsonObject(answer.body());
    String accessToken = issued.getString("access_token");
    JsonObject dockerToken = new JsonObject();
    // `token` is what the docker CLI reads; `access_token` is the same string under the name the
    // OAuth2 half of the spec uses, and clients differ about which they look for. Both, always.
    dockerToken.put("token", accessToken);
    dockerToken.put("access_token", accessToken);
    dockerToken.put("expires_in", issued.getValue("expires_in"));
    dockerToken.put("issued_at", Instant.now().toString());
    request
        .response()
        .setStatusCode(200)
        .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
        // A token response is never cached, anywhere (RFC 6749 §5.1).
        .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        .end(dockerToken.encode());
  }

  private static JsonObject dockerErrors(String code, String message) {
    return new JsonObject()
        .put(
            "errors",
            new JsonArray()
                .add(new JsonObject().put("code", code).put("message", message).putNull("detail")));
  }

  /**
   * The name the client asked for, port included, safe to put inside a quoted header value.
   *
   * <p>The filter is the point: this string is echoed into {@code WWW-Authenticate}, and a Host
   * header holding a quote would otherwise let a caller write their own realm — a header injection
   * that points a docker client at somebody else's token endpoint.
   */
  static String authority(HttpServerRequest request) {
    String host = request.getHeader(HttpHeaders.HOST);
    if (host == null && request.authority() != null) {
      host = request.authority().toString();
    }
    return safeAuthority(host);
  }

  /** The host-name charset and nothing else — see {@link #authority}. */
  static String safeAuthority(String host) {
    if (host == null) {
      return "";
    }
    StringBuilder safe = new StringBuilder(host.length());
    for (int i = 0; i < host.length(); i++) {
      char c = host.charAt(i);
      boolean ok =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '.'
              || c == '-'
              || c == ':';
      if (ok) {
        safe.append(c);
      }
    }
    return safe.toString();
  }

  /** {@code https} when something in front terminated TLS and said so, else the socket's own. */
  private static String scheme(HttpServerRequest request) {
    String forwarded = request.getHeader(EdgeHeaders.PROTO);
    if (forwarded != null && forwarded.equalsIgnoreCase("https")) {
      return "https";
    }
    return request.scheme() == null ? "http" : request.scheme();
  }
}
