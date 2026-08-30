package eu.wohlben.qits.edge;

import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 *
 * <p><b>It is also the incoming tap of {@code ForwardAuthBootstrapIT}'s network diagram.</b> Every
 * sibling service taps its userflow with a RestAssured filter; here that is impossible for the same
 * reason this class exists at all — rest-assured cannot say {@code Host}, so nothing a story sends
 * goes through it. {@link #sending} is the one funnel every ordinary request passes, so the
 * observation lives there. Three things about it:
 *
 * <ul>
 *   <li><b>The vhost is in the label, not just the path.</b> On this service a name is not
 *       decoration — it IS the routing decision, and {@code GET /projects/api/me} means one thing
 *       on a service host and a 404 on the environment door. A label that dropped it would draw
 *       those as one arrow.
 *   <li><b>The initiator is the story's, never the request's.</b> A stranger's browser, a machine
 *       client with no credential and a logged-in person differ by headers a diagram must not print
 *       — and telling them apart is the entire subject. {@link NetworkCapture#actor()} is read when
 *       the request is BUILT, not when the answer arrives, because the answer arrives on a Vert.x
 *       thread at a moment the story does not control.
 *   <li><b>Only answers are observed.</b> A request that failed to get one is not an edge with an
 *       unknown status; it is a test that broke, and {@link #await} says so. That rule now covers
 *       three planes rather than one: {@link #stream} observes its exchange once the whole body has
 *       landed, and {@link #handshake} observes the dial only once the {@code 101} is in hand and
 *       the frame only once it has arrived. A refused upgrade therefore records nothing at all,
 *       which is what stops an "attached" arrow being drawn for a socket that was never opened.
 * </ul>
 *
 * <p><b>All three observations are made on the STORY's own thread</b>, at the point the waiting
 * returned, and never from a Vert.x or JDK callback: {@link NetworkCapture#actor()} is a sticky
 * value the framework resets at every story border, so a callback firing after that border would
 * attribute an edge to the wrong story. The one place that cannot wait — {@link #sending}, whose
 * whole purpose is a request in flight — reads the actor when the request is BUILT and observes
 * before the future completes, which comes to the same guarantee.
 *
 * <p>Nothing about this costs the ordinary {@code @QuarkusTest}s that use this class anything: the
 * capture registry is a JVM-global list that only a running {@code @UserStory} ever drains, so
 * outside a story the observations are recorded and never read.
 */
final class EdgeClient implements AutoCloseable {

  /**
   * What the upstream reported back.
   *
   * @param headers one value per name, the FIRST one received. A response may repeat a name — the
   *     edge sends two {@code WWW-Authenticate} challenges — and a map has to pick one; first is
   *     the one that keeps reading like the wire, since a client that knows one scheme acts on the
   *     first it recognises.
   * @param raw every header line, in order, names lower-cased. The only way to assert a repeated
   *     name and its order — see {@link #headerValues}.
   */
  record Answer(
      int status, Map<String, String> headers, List<Map.Entry<String, String>> raw, String body) {

    /** Every value sent under this name, in wire order. */
    List<String> headerValues(String name) {
      String wanted = name.toLowerCase(java.util.Locale.ROOT);
      return raw.stream().filter(e -> e.getKey().equals(wanted)).map(Map.Entry::getValue).toList();
    }

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

  /** The {@code to} of every edge this tap observes — this service, as the diagram names it. */
  private static final String SERVICE = "qits-platform-edge";

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
    // Read here, on the story's own thread, and kept: see the class comment.
    String caller = NetworkCapture.actor();
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
                          List<Map.Entry<String, String>> raw = new ArrayList<>();
                          response
                              .headers()
                              .forEach(
                                  entry -> {
                                    String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                                    raw.add(Map.entry(name, entry.getValue()));
                                    seen.putIfAbsent(name, entry.getValue());
                                  });
                          // Observed before the future completes, so a story that awaits this
                          // request can never race the edge into its own diagram.
                          NetworkCapture.observe(
                              NetworkEdge.HTTP,
                              caller,
                              SERVICE,
                              label(method, host, uri, response.statusCode()));
                          return new Answer(
                              response.statusCode(), seen, List.copyOf(raw), received.toString());
                        }))
        .onSuccess(answer::complete)
        .onFailure(answer::completeExceptionally);
    return answer;
  }

  /**
   * One observed edge's label: {@code "GET /projects/api/me on projects.dev.example.com -> 200"}.
   *
   * <p>The query string is cut, the convention every service's tap follows — it carries run-local
   * values, and the drained edge set is hashed into the story's {@code networkHash}. The path is
   * left to {@link eu.wohlben.qits.userflows.Labels#scrub}, which {@link NetworkCapture#observe}
   * applies on the way in and which turns {@code /projects/runs/7} into {@code
   * /projects/runs/{id}}.
   */
  private static String label(HttpMethod method, String host, String uri, int status) {
    int query = uri.indexOf('?');
    String path = query < 0 ? uri : uri.substring(0, query);
    return method.name() + " " + path + " on " + host + " -> " + status;
  }

  /** What a chunked read reported: the answer, when its first chunk landed, and the whole body. */
  record Streamed(int status, long firstChunkMillis, String body) {}

  /**
   * Read a chunked response and report when its FIRST chunk arrived, relative to the request. A
   * buffering proxy is invisible to a body assertion and obvious here: it delivers everything at
   * once, at the end.
   *
   * <p><b>Tapped like an ordinary request, and with the same label</b>, because that is what it is:
   * one HTTP exchange with one status. Whether the bytes arrived in one piece or five is a fact
   * about the wire that belongs in the story's assertion rather than in a dependency map — a
   * separate arrow for it would document the upstream's flush boundaries. The observation is made
   * here on the STORY's own thread, after the whole body has landed, so it can never race the drain
   * at a story border.
   */
  Streamed stream(String host, String uri, Map<String, String> headers) {
    // Read here, on the story's own thread, and kept: see the class comment.
    String caller = NetworkCapture.actor();
    CompletableFuture<Integer> status = new CompletableFuture<>();
    CompletableFuture<Long> firstChunk = new CompletableFuture<>();
    CompletableFuture<String> whole = new CompletableFuture<>();
    long start = System.nanoTime();
    client
        .request(options(HttpMethod.GET, host, uri, headers))
        .compose(request -> request.send())
        .onSuccess(
            response -> {
              status.complete(response.statusCode());
              StringBuilder body = new StringBuilder();
              response.handler(
                  chunk -> {
                    firstChunk.complete((System.nanoTime() - start) / 1_000_000);
                    body.append(chunk);
                  });
              response.endHandler(
                  v -> {
                    // An answer with no body at all still ended: completing here keeps a refusal
                    // from waiting out the timeout on a chunk that was never coming.
                    firstChunk.complete((System.nanoTime() - start) / 1_000_000);
                    whole.complete(body.toString());
                  });
              response.exceptionHandler(whole::completeExceptionally);
            })
        .onFailure(
            failure -> {
              status.completeExceptionally(failure);
              firstChunk.completeExceptionally(failure);
              whole.completeExceptionally(failure);
            });
    Streamed streamed = new Streamed(await(status), await(firstChunk), await(whole));
    NetworkCapture.observe(
        NetworkEdge.HTTP, caller, SERVICE, label(HttpMethod.GET, host, uri, streamed.status()));
    return streamed;
  }

  /**
   * Open a WebSocket through the edge and return the first text frame the upstream sends.
   *
   * <p>The JDK client rather than Vert.x's — see the class javadoc for why, and for the system
   * property that lets it name a {@code Host}.
   *
   * <p><b>Two edges, and the split is the report contract's own.</b> The dial is a {@code socket}
   * edge, because what a dependency map should say is that this process holds a connection open to
   * that name; the frame that comes back is an {@code event} edge in the direction it was PUSHED,
   * from the edge to whoever is holding the socket. Both are observed here, on the story thread,
   * after the waiting returned — never from the JDK client's listener callback, which fires on an
   * executor thread at a moment the story does not control and would read whatever actor is current
   * then.
   *
   * <p><b>A refused upgrade records nothing</b>, which is deliberate: it leaves by the throw below,
   * so no "attached" arrow can ever be drawn for a handshake the edge turned away. A story about a
   * refusal sends the handshake as a plain request instead — see {@code SessionlessWallIT} — where
   * the refusal has a real status and draws as the plain 401 it was.
   */
  String handshake(String host, String uri, Map<String, String> headers) {
    String caller = NetworkCapture.actor();
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
    // Only now, with a 101 in hand: the connection is held open, which is the dependency.
    NetworkCapture.observe(NetworkEdge.SOCKET, caller, SERVICE, socketLabel(host, uri, ATTACHED));
    try {
      String frame = await(first);
      NetworkCapture.observe(NetworkEdge.EVENT, SERVICE, caller, FRAME);
      return frame;
    } finally {
      socket.abort();
    }
  }

  /** What a socket edge's label says when the upgrade crossed the edge and the socket was held. */
  static final String ATTACHED = "attached";

  /**
   * The one label every pushed frame collapses into. How many WebSocket messages a stream of output
   * arrives in belongs to the upstream's write boundaries and not to the story, so an arrow per
   * frame would document a buffer size.
   */
  static final String FRAME = "one text frame pushed through the spliced socket";

  /** A socket edge's label: the same shape a request's carries, with a word where a status is. */
  private static String socketLabel(String host, String uri, String outcome) {
    return "GET " + uri + " on " + host + " -> " + outcome;
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
