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
 * addresses reach the route table as {@code qits.edge.upstream-hosts.<env>} and {@code
 * qits.edge.apps.<app>.hosts.<env>} overrides, the config paths that exist for exactly this.
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
 * <p>The stub idp answers the two paths the edge derives from {@code qits.idp.url}: {@code
 * /idp/jwks} publishes {@link TestTokens}' key, and {@code /idp/token} issues one for the clients
 * below. It exists so the auth gate is exercised end to end — a real RS256 signature, a real key
 * fetch, and a real broker hop — rather than against a validator that was told to say yes.
 *
 * <p><b>Three clients, because a credential has three answers.</b> One is commissioned for both
 * environments' registries, one is commissioned for something else entirely — the client that is
 * genuine and still opens nothing here — and one is a black hole the stub accepts and never
 * answers, which is the shape a redeploying idp takes and the only way to prove that the edge
 * answers anyway. {@link #idpDown} and {@link #idpUp} add the fourth shape, a refused connection.
 */
public class StubGateways implements QuarkusTestResourceLifecycleManager {

  /** The client the registry vhosts are for: idp mints it both environments' audiences. */
  static final String CLIENT_ID = "a-client";

  static final String CLIENT_SECRET = "a-secret";

  /** A real client with a real secret, commissioned for an audience no vhost here demands. */
  static final String OTHER_ID = "other-client";

  static final String OTHER_SECRET = "other-secret";

  /** The credential the stub idp accepts a connection for and then never answers. */
  static final String SINKHOLE_ID = "sinkhole";

  static final String SINKHOLE_SECRET = "sinkhole";

  /** The running instance, so a test can take the identity provider away and give it back. */
  private static volatile StubGateways running;

  /** How many grants the stub idp has been asked for — what proves a cache hit made no call. */
  private static final java.util.concurrent.atomic.AtomicInteger GRANTS =
      new java.util.concurrent.atomic.AtomicInteger();

  static int grants() {
    return GRANTS.get();
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
      List.of("X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Qits-User", "Cookie");

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
      config.put("qits.edge.upstream-hosts." + environment, "127.0.0.1:" + listen(environment));
      for (String app : List.of("registry", "mirror")) {
        config.put(
            "qits.edge.apps." + app + ".hosts." + environment,
            "127.0.0.1:" + listen(app + "-" + environment));
      }
    }
    // Required, and unreachable on purpose: every environment above overrides it, so a request that
    // reached this address would be a resolution bug rather than a test that happened to pass.
    config.put("qits.edge.apps.registry.host-pattern", "{env}-qits-artifacts");
    config.put("qits.edge.apps.mirror.host-pattern", "{env}-qits-mirror");
    // ONE of the two apps, which is the point: the exemption is per app label, so the suite has a
    // vhost whose reads are open and a vhost that is not, side by side.
    config.put("qits.edge.auth.anonymous-read-apps", "mirror");
    idpPort = bind("idp", idpServer(), 0);
    config.put("qits.idp.url", "http://127.0.0.1:" + idpPort + "/idp");
    // The three time bounds, shrunk to a suite's patience. Their SHIPPED values are pinned in
    // EdgeChallengeTest instead: a default is a deployment fact and must not be readable from here.
    config.put("qits.edge.auth.basic-cache-ttl-ms", "2000");
    config.put("qits.edge.auth.idp-retry-window-ms", "3000");
    config.put("qits.edge.auth.idp-call-timeout-ms", "1000");
    // qits.edge.auth.audience-pattern is deliberately NOT set: the suite runs against the SHIPPED
    // default, so a change to it is a failing test rather than a silent one.
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
              if (!request.path().equals("/idp/token")) {
                request.response().setStatusCode(404).end();
                return;
              }
              GRANTS.incrementAndGet();
              request.body().onSuccess(body -> grant(request, body.toString()));
            });
  }

  /** The {@code client_credentials} grant, per client. */
  private void grant(HttpServerRequest request, String body) {
    List<String> audiences = audiencesFor(request.getHeader("Authorization"));
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
    request
        .response()
        .putHeader("Content-Type", "application/json")
        .end(
            new io.vertx.core.json.JsonObject()
                .put(
                    "access_token",
                    TestTokens.valid("http://127.0.0.1:" + idpPort + "/idp", audiences))
                .put("token_type", "Bearer")
                .put("expires_in", 300)
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
    if (basic(OTHER_ID, OTHER_SECRET).equals(authorization)) {
      return List.of("somebody-else");
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
    if (vertx != null) {
      vertx.close();
    }
  }
}
