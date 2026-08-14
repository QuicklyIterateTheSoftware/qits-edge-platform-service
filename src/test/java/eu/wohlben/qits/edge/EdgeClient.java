package eu.wohlben.qits.edge;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A client that can say whatever it likes in the {@code Host} header — which is the whole of what
 * this service routes on, so the suite needs one.
 *
 * <p>Vert.x rather than RestAssured or the JDK client, and that is not a preference: RestAssured
 * derives {@code Host} from the URL it was given, and the JDK's {@code HttpClient} lists {@code
 * Host} among the headers an application may not set at all.
 *
 * <p>Vert.x separates the two ideas an edge test needs kept apart: {@code setServer} is where the
 * socket goes, {@code setHost} is what the request says. So an ordinary request states any name at
 * all while still landing on loopback, with no DNS involved.
 *
 * <p><b>A WebSocket handshake is the exception, and it uses the JDK client instead.</b> Vert.x
 * <i>ignores</i> {@code setServer} on {@code HttpClient.webSocket} and resolves the name for real,
 * so every socket test died on {@code UnknownHostException} while the plain requests passed
 * (measured on Vert.x 4.5.26). The JDK's builder will send a chosen {@code Host}, but only with
 * {@code jdk.httpclient.allowRestrictedHeaders=host} — which the surefire configuration in pom.xml
 * sets, and without which these tests fail with an {@code IllegalArgumentException} on the header
 * name rather than on anything about this service.
 */
final class EdgeClient implements AutoCloseable {

  /** What the upstream reported back. */
  record Answer(int status, Map<String, String> headers, String body) {

    /** The value of a {@code name=value} line in a stub gateway's report. */
    String line(String name) {
      return body.lines()
          .filter(l -> l.startsWith(name + "="))
          .map(l -> l.substring(name.length() + 1))
          .findFirst()
          .orElse(null);
    }

    /** The value of a header the upstream saw, or {@code null} when it saw none. */
    String upstreamHeader(String name) {
      return line("header:" + name.toLowerCase(java.util.Locale.ROOT));
    }
  }

  private final Vertx vertx = Vertx.vertx();
  private final HttpClient client = vertx.createHttpClient();
  private final SocketAddress edge;
  private final int port;

  EdgeClient(int port) {
    this.port = port;
    this.edge = SocketAddress.inetSocketAddress(port, "127.0.0.1");
  }

  Answer get(String host, String uri) {
    return send(HttpMethod.GET, host, uri, null, Map.of());
  }

  Answer get(String host, String uri, Map<String, String> headers) {
    return send(HttpMethod.GET, host, uri, null, headers);
  }

  Answer send(
      HttpMethod method, String host, String uri, String body, Map<String, String> headers) {
    return await(sending(method, host, uri, body, headers));
  }

  /**
   * The same request, not waited for. A test that has to change the world WHILE the edge is
   * answering — take the identity provider away and give it back — needs the request in flight.
   */
  CompletableFuture<Answer> sending(
      HttpMethod method, String host, String uri, String body, Map<String, String> headers) {
    RequestOptions options = options(method, host, uri, headers);
    CompletableFuture<Answer> answer = new CompletableFuture<>();
    client
        .request(options)
        .compose(request -> body == null ? request.send() : request.send(Buffer.buffer(body)))
        .compose(
            response ->
                response
                    .body()
                    .map(
                        received -> {
                          Map<String, String> seen = new LinkedHashMap<>();
                          response
                              .headers()
                              .forEach(
                                  entry ->
                                      seen.put(
                                          entry.getKey().toLowerCase(java.util.Locale.ROOT),
                                          entry.getValue()));
                          return new Answer(response.statusCode(), seen, received.toString());
                        }))
        .onSuccess(answer::complete)
        .onFailure(answer::completeExceptionally);
    return answer;
  }

  /**
   * Read a chunked response and report when its FIRST chunk arrived, relative to the request. A
   * buffering proxy is invisible to a body assertion and obvious here: it delivers everything at
   * once, at the end.
   *
   * @return the milliseconds from request to first chunk, and the whole body
   */
  Map.Entry<Long, String> stream(String host, String uri) {
    CompletableFuture<Long> firstChunk = new CompletableFuture<>();
    CompletableFuture<String> whole = new CompletableFuture<>();
    long start = System.nanoTime();
    client
        .request(options(HttpMethod.GET, host, uri, Map.of()))
        .compose(request -> request.send())
        .onSuccess(
            response -> {
              StringBuilder body = new StringBuilder();
              response.handler(
                  chunk -> {
                    firstChunk.complete((System.nanoTime() - start) / 1_000_000);
                    body.append(chunk);
                  });
              response.endHandler(v -> whole.complete(body.toString()));
              response.exceptionHandler(whole::completeExceptionally);
            })
        .onFailure(
            failure -> {
              firstChunk.completeExceptionally(failure);
              whole.completeExceptionally(failure);
            });
    return Map.entry(await(firstChunk), await(whole));
  }

  /**
   * Open a WebSocket through the edge and return the first text frame the upstream sends.
   *
   * <p>The JDK client rather than Vert.x's — see the class javadoc for why, and for the system
   * property that lets it name a {@code Host}.
   */
  String handshake(String host, String uri, Map<String, String> headers) {
    CompletableFuture<String> first = new CompletableFuture<>();
    java.net.http.WebSocket.Builder builder =
        java.net.http.HttpClient.newHttpClient().newWebSocketBuilder().header("Host", host);
    headers.forEach(builder::header);
    java.net.http.WebSocket socket;
    try {
      socket =
          builder
              .buildAsync(
                  java.net.URI.create("ws://127.0.0.1:" + port + uri),
                  new java.net.http.WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public java.util.concurrent.CompletionStage<?> onText(
                        java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
                      buffer.append(data);
                      if (last) {
                        first.complete(buffer.toString());
                      }
                      webSocket.request(1);
                      return null;
                    }
                  })
              .get(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("The edge refused the WebSocket handshake", e);
    }
    try {
      return await(first);
    } finally {
      socket.abort();
    }
  }

  private RequestOptions options(
      HttpMethod method, String host, String uri, Map<String, String> headers) {
    RequestOptions options =
        new RequestOptions()
            .setServer(edge)
            // The authority, i.e. what the request SAYS — the socket still goes to `edge` above.
            .setHost(host)
            .setPort(80)
            .setMethod(method)
            .setURI(uri)
            .setTimeout(TimeUnit.SECONDS.toMillis(30));
    headers.forEach(options::putHeader);
    return options;
  }

  private static <T> T await(CompletableFuture<T> future) {
    try {
      return future.get(30, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("The edge did not answer", e);
    }
  }

  @Override
  public void close() {
    client.close();
    vertx.close();
  }
}
