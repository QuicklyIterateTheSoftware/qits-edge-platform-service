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
 * /idp/jwks} publishes {@link TestTokens}' key, and {@code /idp/token} issues one for a single
 * known client. It exists so the auth gate is exercised end to end — a real RS256 signature, a real
 * key fetch, and a real broker hop — rather than against a validator that was told to say yes.
 */
public class StubGateways implements QuarkusTestResourceLifecycleManager {

  /** The one client id and secret the stub idp knows. */
  static final String CLIENT_ID = "a-client";

  static final String CLIENT_SECRET = "a-secret";

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

  @Override
  public Map<String, String> start() {
    vertx = Vertx.vertx();
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
    config.put("qits.idp.url", "http://127.0.0.1:" + idp() + "/idp");
    // qits.edge.auth.audience-pattern is deliberately NOT set: the suite runs against the SHIPPED
    // default, so a change to it is a failing test rather than a silent one.
    return config;
  }

  /**
   * qits-platform-idp's two paths, as the edge derives them: the published keys and the {@code
   * client_credentials} grant. Form parsing is deliberate rather than Vert.x-assisted — the point
   * is to see the exact bytes the broker sends.
   */
  private int idp() {
    HttpServer server =
        vertx
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
                  request
                      .body()
                      .onSuccess(
                          body -> {
                            String expected =
                                "Basic "
                                    + java.util.Base64.getEncoder()
                                        .encodeToString(
                                            (CLIENT_ID + ":" + CLIENT_SECRET)
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            boolean grant =
                                body.toString().contains("grant_type=client_credentials");
                            if (!expected.equals(request.getHeader("Authorization")) || !grant) {
                              request
                                  .response()
                                  .setStatusCode(401)
                                  .putHeader("Content-Type", "application/json")
                                  .end("{\"error\":\"invalid_client\"}");
                              return;
                            }
                            request
                                .response()
                                .putHeader("Content-Type", "application/json")
                                .end(
                                    new io.vertx.core.json.JsonObject()
                                        .put(
                                            "access_token",
                                            TestTokens.valid(
                                                "http://127.0.0.1:"
                                                    + servers.get("idp").actualPort()
                                                    + "/idp",
                                                List.of(audience("dev"), audience("prod"))))
                                        .put("token_type", "Bearer")
                                        .put("expires_in", 300)
                                        .encode());
                          });
                });
    return bind("idp", server);
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
    return bind(environment, server);
  }

  private int bind(String name, HttpServer server) {
    try {
      servers.put(
          name,
          server
              .listen(0, "127.0.0.1")
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
    if (vertx != null) {
      vertx.close();
    }
  }
}
