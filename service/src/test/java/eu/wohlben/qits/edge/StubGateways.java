package eu.wohlben.qits.edge;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Everything behind the edge, stubbed: two environment gateways, the same two environments' {@code
 * registry} and {@code mirror} applications, and a stand-in qits-platform-idp. Each on an ephemeral
 * loopback port. No docker, no fixture and no fixed port — the whole suite runs from a clone of
 * this repository alone.
 *
 * <p>The gateways are <b>two</b> rather than one so that "the edge chose the right environment" is
 * observable from the outside: each server names itself in every answer, so a test asserts which
 * process received the request rather than only that something did. The application upstreams are
 * two per app for the same reason — an app name has to reach ITS environment's copy. Their
 * addresses reach the route table as {@code qits.edge.apps.<app>.hosts.<env>} overrides — the one
 * config path that exists for exactly this — and, for the two servers this class still calls
 * "gateways", as the suite's OWN {@code qits.test.environment-upstreams.<env>} key, which the tests
 * read to build a deployment endpoint with. The name is historical: there is no per-environment
 * gateway on the platform any more, and these two are simply the far side a projected deployment
 * endpoint points at.
 *
 * <p><b>The applications are two so the auth gate has two answers.</b> {@code mirror} is named in
 * {@code qits.edge.auth.anonymous-read-apps} and {@code registry} is not, so one suite covers both
 * a vhost whose reads are open and a vhost that is gated on every method — with the same upstream
 * shape behind each, so the difference asserted is the edge's decision and nothing else.
 *
 * <p>Vert.x rather than a JDK {@code HttpServer}, because one server has to answer three shapes an
 * edge must pass through unchanged: an ordinary request with a body, a chunked response written
 * over time, and a WebSocket upgrade. A JDK {@code HttpServer} cannot do the third at all.
 *
 * <p>The stub idp answers the three paths the edge derives from {@code qits.idp.url}: {@code
 * /idp/jwks} publishes {@link TestTokens}' key, {@code /idp/token} issues one for the clients
 * below, and {@code /idp/api/sessions/introspect} answers for the browser sessions. It exists so
 * the auth gate is exercised end to end — a real RS256 signature, a real key fetch, a real broker
 * hop and a real introspection — rather than against a validator that was told to say yes.
 *
 * <p><b>Three sessions, because a cookie has three answers.</b> One is live, one is expired and one
 * starts live and can be {@link #revoke revoked} while the suite runs — which is what proves a
 * revocation is obeyed within the cache's own window rather than at the end of it. {@link
 * #introspections()} counts the calls, so a cache hit is provable by the call that did not happen.
 *
 * <p><b>Three clients, because a credential has three answers.</b> One is commissioned for both
 * environments' registries, one is commissioned for something else entirely — the client that is
 * genuine and still opens nothing here — and one is a black hole the stub accepts and never
 * answers, which is the shape a redeploying idp takes and the only way to prove that the edge
 * answers anyway. {@link #idpDown} and {@link #idpUp} add the fourth shape, a refused connection.
 * One more client, {@link #PLATFORM_ID}, holds only the platform audience, which opens every vhost.
 * And {@link #BRIEF_ID} is the fifth shape: commissioned exactly as the first, but issued a token
 * with barely any life left — the credential whose acceptance the edge may not cache, because what
 * a cache entry holds is the token it would forward.
 */
public class StubGateways implements QuarkusTestResourceLifecycleManager {

  /** The client the registry vhosts are for: idp mints it both environments' audiences. */
  static final String CLIENT_ID = "a-client";

  static final String CLIENT_SECRET = "a-secret";

  /** A real client with a real secret, commissioned for an audience no vhost here demands. */
  static final String OTHER_ID = "other-client";

  static final String OTHER_SECRET = "other-secret";

  /**
   * The platform audience: the shipped default of {@code qits.edge.auth.platform-audience}, which
   * the suite does not set. It opens every gated vhost on every tier.
   */
  static final String PLATFORM_AUDIENCE = "qits-platform";

  /** A client commissioned for the platform audience alone. */
  static final String PLATFORM_ID = "platform-client";

  static final String PLATFORM_SECRET = "platform-secret";

  /**
   * A real client, commissioned exactly as {@link #CLIENT_ID} is, whose tokens are nearly over the
   * moment they are minted — inside the edge's own re-mint margin. It is the only way to see the
   * margin from the outside: a belief about this credential can never be cached, because the token
   * it holds would not be worth forwarding by the time it was used.
   */
  static final String BRIEF_ID = "brief-client";

  static final String BRIEF_SECRET = "brief-secret";

  /** {@link #BRIEF_ID}'s token life, well inside {@code EdgeAuth.TOKEN_MARGIN_MS}. */
  static final long BRIEF_TOKEN_SECONDS = 30;

  /** Everyone else's, which is idp's own. */
  private static final long TOKEN_SECONDS = 300;

  /** The credential the stub idp accepts a connection for and then never answers. */
  static final String SINKHOLE_ID = "sinkhole";

  static final String SINKHOLE_SECRET = "sinkhole";

  /**
   * The apex both suites type, and so the one name the edge may serve with no environment label —
   * see {@code EdgeRouter.isApex}. Every origin derived from a name that says nothing else is built
   * on it.
   */
  static final String CANONICAL_ORIGIN = "https://example.com";

  /**
   * The return authorities, with the wildcard that makes every service of an environment one. The
   * apex must be covered or the process refuses to start; the editor's own four-label name is
   * covered by the same wildcard, because its middle label is a project.
   */
  static final String BROWSER_HOSTS =
      "example.com,dev.acme.example.com,prod.acme.example.com,*.dev.acme.example.com";

  /** The edge's OWN idp client, the one it introspects browser sessions with. */
  static final String EDGE_ID = "an-edge";

  static final String EDGE_SECRET = "an-edge-secret";

  /** A live session: the cookie value a browser would send. */
  static final String SESSION = "a-live-session";

  /** One idp refuses because it has run out. */
  static final String EXPIRED_SESSION = "an-expired-session";

  /** Live until {@link #revoke()}, which is how a logout is staged mid-suite. */
  static final String REVOCABLE_SESSION = "a-revocable-session";

  static final String SESSION_USER = "operator";

  static final String SESSION_USER_ID = "b7e4a1c2-0000-4000-8000-00000000beef";

  /** The two rows the register token grants the first account, per the plan. */
  static final List<String> SESSION_ROLES = List.of("qits:admin");

  /** The running instance, so a test can take the identity provider away and give it back. */
  private static volatile StubGateways running;

  /** How many grants the stub idp has been asked for — what proves a cache hit made no call. */
  private static final java.util.concurrent.atomic.AtomicInteger GRANTS =
      new java.util.concurrent.atomic.AtomicInteger();

  /** The same, for introspection. */
  private static final java.util.concurrent.atomic.AtomicInteger INTROSPECTIONS =
      new java.util.concurrent.atomic.AtomicInteger();

  private static volatile boolean revoked;

  static int grants() {
    return GRANTS.get();
  }

  static int introspections() {
    return INTROSPECTIONS.get();
  }

  /** Revoke {@link #REVOCABLE_SESSION} — a logout, as far as the edge can tell. */
  static void revoke() {
    revoked = true;
  }

  /** Put it back, so one test's logout is not every later test's. */
  static void restore() {
    revoked = false;
  }

  /** Stop the stub idp and free its port, so the next call to it is REFUSED. */
  static void idpDown() {
    StubGateways stub = running;
    if (stub != null && stub.servers.containsKey("idp")) {
      stub.servers
          .remove("idp")
          .close()
          .toCompletionStage()
          .toCompletableFuture()
          .orTimeout(10, TimeUnit.SECONDS)
          .join();
    }
  }

  /** Put it back on the SAME port, which is the address the edge was configured with at boot. */
  static void idpUp() {
    StubGateways stub = running;
    if (stub != null && !stub.servers.containsKey("idp")) {
      stub.bind("idp", stub.idpServer(), stub.idpPort);
    }
  }

  /**
   * The audiences the stub idp puts in every token: a client's WHOLE allowed list, which is what a
   * grant naming no audience gets back — and what the live platform's idp does, one value per
   * environment. The edge demands one of them per request, resolved from the vhost's own
   * environment, so a token carrying both is the case that has to keep working while a token
   * carrying one must not cross tiers.
   */
  static String audience(String environment) {
    return environment + "-qits-artifacts";
  }

  /** How long {@code /stream} waits between its two chunks — long enough to time from a client. */
  static final long STREAM_GAP_MILLIS = 400;

  /** The header names a WebSocket handshake reports back, so a test can assert what arrived. */
  static final List<String> REPORTED_HANDSHAKE_HEADERS =
      List.of(
          "X-Forwarded-For",
          "X-Forwarded-Host",
          "X-Forwarded-Proto",
          "X-Qits-User",
          "X-Qits-User-Id",
          "X-Qits-Roles",
          "Cookie");

  private Vertx vertx;
  private final Map<String, HttpServer> servers = new HashMap<>();

  /** Kept so the stub idp can come back on the address the edge already resolved. */
  private int idpPort;

  @Override
  public Map<String, String> start() {
    vertx = Vertx.vertx();
    running = this;
    Map<String, String> config = new HashMap<>();
    config.put("qits.edge.environments", "prod,dev");
    config.put("qits.edge.default-environment", "prod");
    for (String environment : List.of("prod", "dev")) {
      config.put(
          "qits.test.environment-upstreams." + environment, "127.0.0.1:" + listen(environment));
      for (String app : List.of("registry", "mirror", "editor")) {
        config.put(
            "qits.edge.apps." + app + ".hosts." + environment,
            "127.0.0.1:" + listen(app + "-" + environment));
      }
    }
    // Required, and unreachable on purpose: every environment above overrides it, so a request that
    // reached this address would be a resolution bug rather than a test that happened to pass.
    config.put("qits.edge.apps.registry.host-pattern", "{env}-qits-artifacts");
    config.put("qits.edge.apps.mirror.host-pattern", "{env}-qits-mirror");
    // The third app is the editor, one shared container for the whole platform on an ordinary app
    // vhost: `editor.<env>.<domain>`. Its two upstreams are what makes "the named environment, not
    // the default" an assertion about which process answered rather than about a status code. It
    // still answers on the four-label tier too, which is what those routing tests read.
    config.put("qits.edge.apps.editor.host-pattern", "{env}-qits-workspaces");
    // Every app vhost here demands the tier-scoped `{env}-qits-artifacts` audience explicitly —
    // mirroring a live deployment's GITHOST/EDITOR extras, which name a resource pattern of their
    // own. The shipped DEFAULT of this key is now the literal `qits-platform`, pinned instead in
    // EdgeChallengeTest, so a change to it is a failing test rather than a silent one here.
    config.put("qits.edge.apps.registry.audience-pattern", "{env}-qits-artifacts");
    config.put("qits.edge.apps.mirror.audience-pattern", "{env}-qits-artifacts");
    config.put("qits.edge.apps.editor.audience-pattern", "{env}-qits-artifacts");
    // ONE of the two CONFIGURED apps, which is the point: the exemption is per app label, so the
    // suite has a vhost whose reads are open and a vhost that is not, side by side.
    //
    // `brochure` is the same pair again for the OTHER way a label reaches that gate. It is named
    // here and configured NOWHERE — there is deliberately no `qits.edge.apps.brochure.*` entry of
    // any kind — so HostEnvironments can only ever answer it as an unknown app, and the only thing
    // that can make it an app route is the deployment projection, which EdgeRouter.target()
    // rebuilds the Route from. Adding an entry for it would quietly turn its coverage into a
    // second copy of the `mirror` case. It stands for any public SSR page the edge knows only
    // because a DeploymentActive said so. `ci` is projected the same way and is NOT named here, so
    // the suite has a projected-open name and a projected-gated name side by side, exactly as it
    // already has for the configured pair.
    //
    // It is deliberately NOT called `landing`: that label is reserved for a project's own root and
    // is a 404 at every app position, so a fixture spelled that way would be testing the reserved
    // reading rather than the projected one. See HostEnvironments.LANDING.
    config.put("qits.edge.auth.anonymous-read-apps", "mirror,brochure");
    idpPort = bind("idp", idpServer(), 0);
    config.put("qits.idp.url", "http://127.0.0.1:" + idpPort + "/idp");
    // The three time bounds, shrunk to a suite's patience. Their SHIPPED values are pinned in
    // EdgeChallengeTest instead: a default is a deployment fact and must not be readable from here.
    config.put("qits.edge.auth.basic-cache-ttl-ms", "2000");
    config.put("qits.edge.auth.idp-retry-window-ms", "3000");
    config.put("qits.edge.auth.idp-call-timeout-ms", "1000");
    // The session gate's own credential and time bounds. Shipped here rather than in the profile
    // that turns the gate ON, because they are facts about this stub idp: the credential it accepts
    // and the patience its ports deserve. The FLAG stays the profile's, which is what lets the same
    // resource serve both a suite with the gate off and one with it on.
    config.put("qits.edge.sessions.client-id", EDGE_ID);
    config.put("qits.edge.sessions.client-secret", EDGE_SECRET);
    // The names, which are facts about this fixture rather than about the gate: the apex the suite
    // types is `example.com`, and the edge has to be told so — it is what the door is recognised
    // by and what every derived origin is built from when a name says nothing else. Here rather
    // than in the profile that turns the gate ON, so both suites read the same domain.
    config.put("qits.edge.sessions.canonical-origin", CANONICAL_ORIGIN);
    config.put("qits.edge.sessions.browser-hosts", BROWSER_HOSTS);
    config.put("qits.edge.sessions.cache-ttl-ms", "1000");
    config.put("qits.edge.sessions.stale-grace-ms", "8000");
    // The environment vhost's own gate falls back to this GLOBAL pattern for a name none of the
    // three apps above claims — a PUBLISHED (deployment-projected) service such as `ci` in
    // EdgeRoutingTest. Explicitly set for the same reason as the three per-app entries above: the
    // SHIPPED default is now `qits-platform`, and this suite still exercises an explicitly
    // configured, tier-scoped pattern.
    config.put("qits.edge.auth.audience-pattern", "{env}-qits-artifacts");
    return config;
  }

  /**
   * qits-platform-idp's two paths, as the edge derives them: the published keys and the {@code
   * client_credentials} grant. Form parsing is deliberate rather than Vert.x-assisted — the point
   * is to see the exact bytes the broker sends.
   */
  private HttpServer idpServer() {
    return vertx
        .createHttpServer()
        .requestHandler(
            request -> {
              if (request.path().equals("/idp/jwks")) {
                request
                    .response()
                    .putHeader("Content-Type", "application/json")
                    .end(TestTokens.jwks().encode());
                return;
              }
              if (request.path().equals("/idp/api/sessions/introspect")) {
                INTROSPECTIONS.incrementAndGet();
                request.body().onSuccess(body -> introspect(request, body.toString()));
                return;
              }
              if (!request.path().equals("/idp/token")) {
                request.response().setStatusCode(404).end();
                return;
              }
              GRANTS.incrementAndGet();
              request.body().onSuccess(body -> grant(request, body.toString()));
            });
  }

  /**
   * {@code POST /idp/api/sessions/introspect} — the edge's own client id and secret in HTTP Basic,
   * the cookie value in the body, and the user it belongs to out.
   *
   * <p>Everything it refuses is a <b>non-200</b>, which is the contract: an unknown, expired or
   * revoked session is idp DECIDING, and the edge must not retry it or cache it.
   */
  private void introspect(HttpServerRequest request, String body) {
    if (!basic(EDGE_ID, EDGE_SECRET).equals(request.getHeader("Authorization"))) {
      // The introspection endpoint is not an oracle: without the edge's own credential, nobody gets
      // to ask this stub about anybody's cookie either.
      request.response().setStatusCode(401).end();
      return;
    }
    String token = new io.vertx.core.json.JsonObject(body).getString("token");
    boolean live = SESSION.equals(token) || (REVOCABLE_SESSION.equals(token) && !revoked);
    if (!live) {
      request.response().setStatusCode(404).end();
      return;
    }
    request
        .response()
        .putHeader("Content-Type", "application/json")
        .end(
            new io.vertx.core.json.JsonObject()
                .put("userId", SESSION_USER_ID)
                .put("username", SESSION_USER)
                .put("roles", new io.vertx.core.json.JsonArray(SESSION_ROLES))
                .put(
                    "expiresAt",
                    java.time.Instant.now().plus(java.time.Duration.ofHours(12)).toString())
                .encode());
  }

  /** The {@code client_credentials} grant, per client. */
  private void grant(HttpServerRequest request, String body) {
    String authorization = request.getHeader("Authorization");
    List<String> audiences = audiencesFor(authorization);
    if (audiences == null || !body.contains("grant_type=client_credentials")) {
      request
          .response()
          .setStatusCode(401)
          .putHeader("Content-Type", "application/json")
          .end("{\"error\":\"invalid_client\"}");
      return;
    }
    if (audiences.isEmpty()) {
      // The black hole: the connection was accepted and is never answered, which is what a
      // container that is being replaced does to a request that reached it a moment too early.
      return;
    }
    long life =
        basic(BRIEF_ID, BRIEF_SECRET).equals(authorization) ? BRIEF_TOKEN_SECONDS : TOKEN_SECONDS;
    request
        .response()
        .putHeader("Content-Type", "application/json")
        .end(
            new io.vertx.core.json.JsonObject()
                .put(
                    "access_token",
                    TestTokens.validFor("http://127.0.0.1:" + idpPort + "/idp", audiences, life))
                .put("token_type", "Bearer")
                .put("expires_in", life)
                .encode());
  }

  /**
   * The audiences this credential is commissioned for: null when the stub knows no such client, and
   * an EMPTY list for the credential that is never answered at all.
   */
  private static List<String> audiencesFor(String authorization) {
    if (basic(CLIENT_ID, CLIENT_SECRET).equals(authorization)) {
      return List.of(audience("dev"), audience("prod"));
    }
    if (basic(BRIEF_ID, BRIEF_SECRET).equals(authorization)) {
      return List.of(audience("dev"), audience("prod"));
    }
    if (basic(OTHER_ID, OTHER_SECRET).equals(authorization)) {
      return List.of("somebody-else");
    }
    if (basic(PLATFORM_ID, PLATFORM_SECRET).equals(authorization)) {
      return List.of(PLATFORM_AUDIENCE);
    }
    if (basic(SINKHOLE_ID, SINKHOLE_SECRET).equals(authorization)) {
      return List.of();
    }
    return null;
  }

  static String basic(String id, String secret) {
    return "Basic "
        + java.util.Base64.getEncoder()
            .encodeToString((id + ":" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private int listen(String environment) {
    HttpServer server =
        vertx
            .createHttpServer()
            .requestHandler(request -> answer(environment, request))
            .webSocketHandler(
                socket -> {
                  if (socket.path().endsWith("/refused")) {
                    // An upstream that accepts the TCP leg and refuses the upgrade — the shape
                    // that leaked a pool slot per attempt. See the leak regression in
                    // EdgeRoutingTest.
                    socket.reject(403);
                    return;
                  }
                  StringBuilder seen =
                      new StringBuilder("upstream=").append(environment).append('\n');
                  for (String name : REPORTED_HANDSHAKE_HEADERS) {
                    String value = socket.headers().get(name);
                    seen.append(name.toLowerCase(java.util.Locale.ROOT))
                        .append('=')
                        .append(value == null ? "-" : value)
                        .append('\n');
                  }
                  socket.writeTextMessage(seen.toString());
                });
    return bind(environment, server, 0);
  }

  /**
   * @param port 0 for one the kernel picks; a number to come back on the one already published
   */
  private int bind(String name, HttpServer server, int port) {
    try {
      servers.put(
          name,
          server
              .listen(port, "127.0.0.1")
              .toCompletionStage()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS));
    } catch (Exception e) {
      throw new IllegalStateException("Could not start the stub upstream " + name, e);
    }
    return servers.get(name).actualPort();
  }

  private void answer(String environment, HttpServerRequest request) {
    if (request.path().contains("/spa/")) {
      // The shape a Quinoa-served SPA has: every static resource carries the Quarkus default
      // whether or not its name is content-hashed, and a handler that made a decision of its own
      // carries that decision instead. Which of those the edge may correct is EdgeCacheControl's
      // whole subject, so all three spellings are served here rather than only the interesting one.
      request
          .response()
          .putHeader("Content-Type", "text/plain; charset=utf-8")
          .putHeader("X-Upstream", environment)
          .putHeader(
              "Cache-Control",
              request.path().endsWith("/private") ? "no-store" : "public, immutable, max-age=86400")
          .end("upstream=" + environment + "\n");
      return;
    }
    if (request.path().equals("/stream")) {
      // Two chunks with a measurable gap. A proxy that buffered the response would deliver both at
      // once, and the client's timing is what catches that — a body assertion alone would not.
      HttpServerResponse response = request.response().setChunked(true);
      response.putHeader("Content-Type", "text/plain; charset=utf-8");
      response.write("chunk-1\n");
      vertx.setTimer(
          STREAM_GAP_MILLIS,
          id -> {
            response.write("chunk-2\n");
            response.end();
          });
      return;
    }
    request
        .body()
        .onSuccess(
            body -> {
              StringBuilder report =
                  new StringBuilder()
                      .append("upstream=")
                      .append(environment)
                      .append("\nmethod=")
                      .append(request.method())
                      .append("\nuri=")
                      .append(request.uri())
                      .append("\nbody-bytes=")
                      .append(body.length())
                      .append("\nbody=")
                      .append(body.toString())
                      .append('\n');
              // Every header verbatim, so a test can assert BOTH that a name arrived and that a
              // name did not — the edge strips nothing, and an allow-list here could not show it.
              request
                  .headers()
                  .forEach(
                      entry ->
                          report
                              .append("header:")
                              .append(entry.getKey().toLowerCase(java.util.Locale.ROOT))
                              .append('=')
                              .append(entry.getValue())
                              .append('\n'));
              request
                  .response()
                  .putHeader("Content-Type", "text/plain; charset=utf-8")
                  .putHeader("X-Upstream", environment)
                  .end(report.toString());
            });
  }

  @Override
  public void stop() {
    running = null;
    revoked = false;
    if (vertx != null) {
      vertx.close();
    }
  }
}
