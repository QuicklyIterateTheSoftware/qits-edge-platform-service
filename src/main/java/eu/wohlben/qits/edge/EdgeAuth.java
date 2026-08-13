package eu.wohlben.qits.edge;

import io.vertx.core.Future;
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
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Locale;
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

  @Inject Vertx vertx;

  @Inject AuthConfig config;

  @Inject Idp idp;

  @Inject IdpKeys keys;

  private HttpClient client;

  @PostConstruct
  void open() {
    client = vertx.createHttpClient();
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
    if (!enforce) {
      return Future.succeededFuture(null);
    }
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.toLowerCase(Locale.ROOT).startsWith(BEARER)) {
      return Future.succeededFuture("no bearer token");
    }
    SignedJwt jwt;
    try {
      jwt = SignedJwt.parse(header.substring(BEARER.length()).trim());
    } catch (IllegalArgumentException e) {
      return Future.succeededFuture(e.getMessage());
    }
    String problem =
        jwt.problem(idp.issuer(), config.audience(), Instant.now(), config.clockSkewSeconds());
    if (problem != null) {
      // Claims before signature: a claim check needs no key, so an expired or misaddressed token is
      // refused without a JWKS lookup — and a made-up kid cannot use one to force a fetch.
      return Future.succeededFuture(problem);
    }
    return keys.find(jwt.kid())
        .map(key -> jwt.signatureMatches(key) ? null : "the token's signature does not verify");
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
   * verbatim — this process never sees them decoded, never stores them, and makes no decision about
   * them; idp authenticates the client and decides its audiences, exactly as it does for every
   * other machine caller on the platform.
   */
  public void token(HttpServerRequest request) {
    String basic = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (basic == null || !basic.toLowerCase(Locale.ROOT).startsWith(BASIC)) {
      // Basic, not Bearer: this is the endpoint that SELLS bearer tokens, so asking for one here
      // would be a loop. docker sends the stored `docker login` credential when it sees this.
      request
          .response()
          .setStatusCode(401)
          .putHeader(WWW_AUTHENTICATE, "Basic realm=\"" + authority(request) + "\"")
          .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
          .end(dockerErrors("UNAUTHORIZED", "client credentials required").encode());
      return;
    }
    LOG.debugf(
        "token request for service=%s scope=%s",
        request.getParam("service"), request.getParam("scope"));

    RequestOptions options =
        new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI(idp.tokenEndpoint());
    client
        .request(options)
        .compose(
            idpRequest -> {
              idpRequest.putHeader(HttpHeaders.AUTHORIZATION, basic);
              idpRequest.putHeader(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded");
              idpRequest.putHeader(HttpHeaders.ACCEPT, "application/json");
              // NO `audience` parameter, which asks idp for the client's whole allowed list. The
              // alternative — naming the audience here — makes a client that lacks it fail at idp
              // with an invalid_target the caller cannot read. Asking for everything and checking
              // the audience on the way back in puts the refusal where the reason is known.
              return idpRequest.send("grant_type=client_credentials");
            })
        .compose(
            response ->
                response.body().map(body -> new IdpAnswer(response.statusCode(), body.toString())))
        .onSuccess(answer -> relay(request, answer))
        .onFailure(
            failure -> {
              LOG.errorf(failure, "could not reach %s", idp.tokenEndpoint());
              request
                  .response()
                  .setStatusCode(502)
                  .putHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                  .end(
                      dockerErrors("UNAVAILABLE", "the identity provider could not be reached")
                          .encode());
            });
  }

  private record IdpAnswer(int status, String body) {}

  /** idp's RFC 6749 token response, redressed as the Distribution spec's. */
  private void relay(HttpServerRequest request, IdpAnswer answer) {
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
