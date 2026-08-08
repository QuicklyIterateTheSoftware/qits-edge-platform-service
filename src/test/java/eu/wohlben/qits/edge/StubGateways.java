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
 * Two throwaway environment gateways, one per configured environment, each on an ephemeral loopback
 * port. No docker, no fixture and no fixed port — the whole suite runs from a clone of this
 * repository alone.
 *
 * <p>They are <b>two</b> rather than one so that "the edge chose the right environment" is
 * observable from the outside: each server names itself in every answer, so a test asserts which
 * process received the request rather than only that something did. Their addresses reach the route
 * table as {@code qits.edge.upstream-hosts.<env>} overrides, which is the config path that exists
 * for exactly this and for a developer's local gateway.
 *
 * <p>Vert.x rather than a JDK {@code HttpServer}, because one server has to answer three shapes an
 * edge must pass through unchanged: an ordinary request with a body, a chunked response written
 * over time, and a WebSocket upgrade. A JDK {@code HttpServer} cannot do the third at all.
 */
public class StubGateways implements QuarkusTestResourceLifecycleManager {

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
    }
    return config;
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
    try {
      servers.put(
          environment,
          server
              .listen(0, "127.0.0.1")
              .toCompletionStage()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS));
    } catch (Exception e) {
      throw new IllegalStateException("Could not start the stub gateway for " + environment, e);
    }
    return servers.get(environment).actualPort();
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
