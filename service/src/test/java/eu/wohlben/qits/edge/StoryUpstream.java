package eu.wohlben.qits.edge;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * <b>One service behind the edge, standing on a real port, recording what ARRIVED.</b>
 *
 * <p>This is the far side of every story in this catalogue, and the far side is where the evidence
 * is. What a story can see from in front of the edge is a status code; what a service behind it
 * would have BELIEVED is a header map, and only the receiver holds one. Every claim this repository
 * makes about the platform's trust model — the reserved namespace is emptied, an identity idp
 * vouched for is written in its place, a person's cookie does not reach a machine vhost, a name is
 * the whole routing decision — is a claim about bytes that arrived somewhere else.
 *
 * <h2>Why not {@code MockService}</h2>
 *
 * <p>qits-service-mock is the platform's generic recording stand-in and it is still used here: it
 * impersonates <b>qits-platform-idp</b>, whose whole contribution to these stories is a canned JSON
 * introspection answer, which is exactly what it is for. It cannot stand in for an APPLICATION
 * behind this service, on four counts, and each of them is a story:
 *
 * <ul>
 *   <li><b>It cannot choose a response header.</b> {@code EdgeCacheControl} rewrites {@code
 *       Cache-Control: public, immutable, max-age=86400} into {@code no-cache} on everything whose
 *       name is not content-hashed — the fix that was lost when the gateway was retired and this
 *       process took its place. A stand-in that cannot SEND that header cannot be used to prove the
 *       edge corrects it.
 *   <li><b>It cannot answer with anything but JSON.</b> The document the rewrite exists for is an
 *       {@code index.html}.
 *   <li><b>It cannot answer over time.</b> {@code vertx-http-proxy} never buffers, which is the
 *       reason this service has the shape it has; a stand-in that writes its whole body at once
 *       makes a buffering proxy and a streaming one indistinguishable.
 *   <li><b>It cannot stop answering, and it cannot speak WebSocket.</b> An upstream that goes away
 *       mid-request is what a 502 is FOR, and an interactive terminal is the one plane on which a
 *       forged {@code X-Qits-User} used to cross this process ({@code EdgeWebSocketUpgrade} is the
 *       edge's own path and never runs the interceptor chain).
 * </ul>
 *
 * <p>So the applications are Vert.x servers, for the same reason {@link StubGateways} is one: a JDK
 * {@code HttpServer} cannot do the WebSocket half at all.
 *
 * <h2>Two processes and more than one classloader</h2>
 *
 * <p>The server is dialled by the LAUNCHED artifact, a different process, so it has to be a real
 * socket on a real port. It is also started from a {@code QuarkusTestProfile}, which Quarkus
 * instantiates in more than one classloader — so a plain static singleton exists twice and the copy
 * a story arms is not the copy the application talks to. Both problems have the one answer this
 * platform already uses: the address is parked in a <b>system property</b>, the one namespace every
 * classloader in a JVM shares, and every mutation and every read is an HTTP call to the server
 * itself. The second instance is simply a client of the first.
 *
 * <h2>Recording discipline</h2>
 *
 * <ul>
 *   <li><b>A request is recorded BEFORE it is answered</b> — including one that is never answered
 *       at all. The outage arm closes the connection with no status on the wire, and a recording
 *       written afterwards would miss precisely the exchange that story is about.
 *   <li><b>The recording is wiped when the server starts and never again.</b> {@link
 *       eu.wohlben.qits.userflows.NetworkCapture#source} attributes a cumulative recording with a
 *       cursor, and a reset mid-run would re-attribute traffic to whichever story drained next. One
 *       process, one recording, one cursor.
 *   <li><b>Every header is kept, and that is the point.</b> An allow-list could show that a header
 *       arrived and never that one did not, and "the forgery is not here" is the sharper half of
 *       every claim below.
 *   <li><b>The status is recorded beside the request</b>, because a diagram's label needs the
 *       answer, not the intention — and on a proxy the status is what says whether the hop happened
 *       at all.
 * </ul>
 */
public final class StoryUpstream {

  /** Where a started server parks its address, per name. */
  private static final String ANCHOR_PREFIX = "qits.test.story-upstream.";

  /** Everything under this prefix is control traffic: never recorded, never served, never drawn. */
  private static final String CONTROL = "/__story/";

  /**
   * The status a recorded line carries when the connection went away with no response at all.
   * Deliberately a word rather than a number: nothing was on the wire, and writing {@code 000}
   * would put a status in a diagram where none was ever sent.
   */
  public static final String DROPPED = "dropped";

  /** What the recording says a request carried when it carried nothing under that name. */
  public static final String ABSENT = "-";

  private static final Map<String, StoryUpstream> INSTANCES = new ConcurrentHashMap<>();

  private final String name;
  private final String baseUrl;
  private final String address;
  private final HttpClient http =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  // Populated only in the copy that actually started the server; the other one is a client of it.
  private final Map<String, Answer> answers = new ConcurrentHashMap<>();
  private final List<JsonObject> recording = Collections.synchronizedList(new ArrayList<>());
  private volatile boolean dropping;
  private Vertx vertx;

  private StoryUpstream(String name) {
    this.name = name;
    String anchor = ANCHOR_PREFIX + name;
    String existing = System.getProperty(anchor);
    if (existing != null) {
      this.baseUrl = existing;
      this.address = URI.create(existing).getAuthority();
      return;
    }
    this.vertx = Vertx.vertx();
    HttpServer server =
        vertx
            .createHttpServer()
            .requestHandler(this::handle)
            // Set separately, because Vert.x routes an upgrade here and never to the request
            // handler. It is also the only way a stand-in speaks the plane an interactive terminal
            // travels on.
            .webSocketHandler(this::handshake);
    int port;
    try {
      port =
          server
              .listen(0, "127.0.0.1")
              .toCompletionStage()
              .toCompletableFuture()
              .get(20, TimeUnit.SECONDS)
              .actualPort();
    } catch (Exception unstartable) {
      throw new IllegalStateException("could not start the story upstream " + name, unstartable);
    }
    this.address = "127.0.0.1:" + port;
    this.baseUrl = "http://" + address;
    System.setProperty(anchor, baseUrl);
  }

  /**
   * The one server for {@code name}, started on the first call in this JVM and attached to
   * afterwards — what {@link StoryProfile} calls, because the launch command is built out of these
   * addresses.
   *
   * @param name how the network diagram names this service — {@code qits-projects}, {@code
   *     qits-docs} — deliberately the service a deployment really routes to rather than a fixture's
   *     alias, so a reader of the diagram sees the dependency the configuration declares
   */
  public static StoryUpstream named(String name) {
    return INSTANCES.computeIfAbsent(name, StoryUpstream::new);
  }

  /**
   * The already-started server for {@code name} — what a <b>story class</b> calls.
   *
   * <p>The distinction from {@link #named} is a guard rather than style. {@code named} starts a
   * server when it finds no anchor, which is right exactly once and catastrophic afterwards: a
   * story that started a SECOND qits-projects would arm its answers on a port the launched process
   * has never heard of, every read would 404 against the first server, and the failure would name a
   * missing route rather than the mistake.
   */
  public static StoryUpstream attach(String name) {
    if (System.getProperty(ANCHOR_PREFIX + name) == null && !INSTANCES.containsKey(name)) {
      throw new IllegalStateException(
          "no story upstream is running for "
              + name
              + " — StoryProfile starts them all, so a story class reaching this has been run"
              + " without it");
    }
    return named(name);
  }

  /** How the diagram names this service — also the {@code to} of every edge it produces. */
  public String name() {
    return name;
  }

  /** {@code host:port}, which is what {@code qits.edge.apps.<app>.hosts.<env>} takes. */
  public String address() {
    return address;
  }

  // --- what a story arms -------------------------------------------------------------------------

  /** A JSON document at exactly {@code path}, 200, no extra headers. */
  public StoryUpstream json(String path, String body) {
    return answer(path, 200, "application/json", body, Map.of());
  }

  /**
   * A document with response headers — the arm the cache story is built on. {@code Cache-Control}
   * is a header only the ORIGIN can send, and which of them the edge may correct is the whole
   * subject of {@link EdgeCacheControl}.
   */
  public StoryUpstream answer(
      String path, int status, String contentType, String body, Map<String, String> headers) {
    JsonObject arm =
        new JsonObject()
            .put("path", path)
            .put("status", status)
            .put("contentType", contentType)
            .put("body", body)
            .put("headers", new JsonObject(new LinkedHashMap<>(headers)));
    control("serve", arm);
    return this;
  }

  /**
   * An answer written over time: every chunk but the last, then {@code gapMillis}, then the rest.
   *
   * <p>A proxy that buffered would deliver the whole body at the end and no body assertion could
   * tell; the client's own timing is what catches it — see {@code EdgeClient.stream}.
   */
  public StoryUpstream chunked(
      String path, String contentType, List<String> chunks, long gapMillis) {
    JsonObject arm =
        new JsonObject()
            .put("path", path)
            .put("status", 200)
            .put("contentType", contentType)
            .put("chunks", new JsonArray(new ArrayList<Object>(chunks)))
            .put("gapMillis", gapMillis);
    control("serve", arm);
    return this;
  }

  /**
   * A WebSocket endpoint at exactly {@code path} that accepts the upgrade and pushes one text
   * frame. A path with no socket arm refuses the upgrade, which is the other shape an upstream can
   * take.
   */
  public StoryUpstream socket(String path, String frame) {
    control("serve", new JsonObject().put("path", path).put("frame", frame));
    return this;
  }

  /**
   * Whether this service answers at all.
   *
   * <p>{@code true} means the connection goes away with no status and no body, which is what a
   * container being replaced looks like to {@code vertx-http-proxy} — and a 500 would be a service
   * having an opinion, which is a different story. <b>Armable from a story method</b>, which is the
   * whole point: the service goes dark between two reads and the story watches what the caller is
   * told.
   */
  public void dropping(boolean value) {
    control("dropping", new JsonObject().put("value", value));
  }

  // --- what the tap and the assertions read ------------------------------------------------------

  /**
   * One exchange this service was given: what arrived, what it answered, and every header verbatim.
   *
   * @param kind {@code http} or {@code socket} — a handshake is a different plane and a diagram
   *     draws it differently
   * @param status what this side answered, or {@link #DROPPED}
   */
  public record Request(
      String kind, String method, String path, String status, Map<String, String> headers) {

    /** The label half of an edge: method, path, and the status that came back. */
    public String label() {
      return method + " " + path + " -> " + status;
    }

    /** One header's value as it ARRIVED, or {@link #ABSENT} when the request carried none. */
    public String header(String header) {
      String value = headers.get(header);
      return value == null ? ABSENT : value;
    }
  }

  /**
   * The <b>whole</b> recording, every time — the contract {@link
   * eu.wohlben.qits.userflows.NetworkCapture#source} states, with the framework's per-source cursor
   * deciding which slice belongs to the story now draining.
   */
  public List<Request> recordedRequests() {
    JsonArray lines = new JsonArray(controlText("recording"));
    List<Request> requests = new ArrayList<>();
    for (int index = 0; index < lines.size(); index++) {
      JsonObject line = lines.getJsonObject(index);
      // CASE-INSENSITIVE, and it has to stay that way through to the assertion. Header names are
      // case-insensitive on the wire and every hop spells them differently: a client writes
      // `X-Qits-User`, vertx-http-proxy writes the authority back as a lower-case `host`, and
      // Vert.x's server preserves whatever arrived. Note this is deliberately NOT `Map.copyOf`,
      // which silently drops the comparator and turns every lookup back into an exact match.
      Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      line.getJsonObject("headers")
          .forEach(entry -> headers.put(entry.getKey(), String.valueOf(entry.getValue())));
      requests.add(
          new Request(
              line.getString("kind"),
              line.getString("method"),
              line.getString("path"),
              line.getString("status"),
              Collections.unmodifiableMap(headers)));
    }
    return requests;
  }

  /** How many times this service was asked for exactly {@code path}, whatever it answered. */
  public long requestsTo(String path) {
    return recordedRequests().stream().filter(request -> path.equals(request.path())).count();
  }

  /**
   * The one exchange that reached {@code path}, or a failure naming how many did. Exactly one, so a
   * retry or a duplicated proxy hop can never make a header assertion read the wrong request.
   */
  public Request onlyRequestTo(String path) {
    List<Request> matched =
        recordedRequests().stream().filter(request -> path.equals(request.path())).toList();
    if (matched.size() != 1) {
      throw new AssertionError(
          "exactly one request must have reached "
              + name
              + path
              + ", but "
              + matched.size()
              + " did: "
              + matched.stream().map(Request::label).toList());
    }
    return matched.getFirst();
  }

  // --- the server --------------------------------------------------------------------------------

  private void handle(HttpServerRequest request) {
    String path = request.path();
    // Control first, and before the drop switch: turning a service back on has to work while it is
    // off, and no control call is ever traffic a diagram should draw.
    if (path.startsWith(CONTROL)) {
      request.body().onSuccess(body -> control(request, path.substring(CONTROL.length()), body));
      return;
    }
    // The body is drained before anything is answered, whatever the answer turns out to be. A
    // response written while an inbound body is still unread leaves the connection in a state
    // keep-alive cannot recover from, and the proxy in front of this holds its connections open.
    request.body().onSuccess(ignored -> serve(request, path));
  }

  private void serve(HttpServerRequest request, String path) {
    if (dropping) {
      // Recorded BEFORE the connection goes away — this is the one exchange whose evidence would
      // otherwise not exist, and it is the evidence the outage story is entirely about.
      record("http", request.method().name(), path, DROPPED, request.headers());
      request.connection().close();
      return;
    }
    Answer armed = answers.get(path);
    if (armed == null) {
      // An unarmed route is this service's genuine "no such thing", and it is deliberately still
      // RECORDED: "the refused request never reached the service" is asserted on this list rather
      // than inferred from a status the edge chose.
      record("http", request.method().name(), path, "404", request.headers());
      request
          .response()
          .setStatusCode(404)
          .putHeader("Content-Type", "application/json")
          .end("{\"error\":\"" + name + " has no answer armed for this route\"}");
      return;
    }
    record("http", request.method().name(), path, String.valueOf(armed.status), request.headers());
    HttpServerResponse response = request.response().setStatusCode(armed.status);
    if (armed.contentType != null) {
      response.putHeader("Content-Type", armed.contentType);
    }
    armed.headers.forEach(response::putHeader);
    if (armed.chunks == null) {
      response.end(armed.body == null ? "" : armed.body);
      return;
    }
    response.setChunked(true);
    for (int index = 0; index < armed.chunks.size() - 1; index++) {
      response.write(armed.chunks.get(index));
    }
    vertx.setTimer(
        armed.gapMillis,
        timer -> {
          response.write(armed.chunks.getLast());
          response.end();
        });
  }

  /**
   * The upgrade plane. Recorded exactly like a request, with the handshake's own headers — which is
   * where the forward-auth claim is checkable on the ONE path that never reaches the edge's
   * interceptor chain.
   */
  private void handshake(io.vertx.core.http.ServerWebSocket socket) {
    Answer armed = answers.get(socket.path());
    if (armed == null || armed.frame == null) {
      // An upstream that accepts the TCP leg and refuses the upgrade. Recorded first: a refusal is
      // an exchange, and the caller is told the upstream's own answer.
      record("socket", "GET", socket.path(), "426", socket.headers());
      socket.reject(426);
      return;
    }
    record("socket", "GET", socket.path(), "101", socket.headers());
    socket.writeTextMessage(armed.frame);
  }

  private void record(
      String kind, String method, String path, String status, io.vertx.core.MultiMap headers) {
    JsonObject seen = new JsonObject();
    // First value per name, like every other recorder on this platform: a repeated request header
    // is not a shape any assertion here is about.
    headers.forEach(entry -> seen.put(entry.getKey(), entry.getValue()));
    recording.add(
        new JsonObject()
            .put("kind", kind)
            .put("method", method)
            .put("path", path)
            .put("status", status)
            .put("headers", seen));
  }

  private void control(
      HttpServerRequest request, String command, io.vertx.core.buffer.Buffer body) {
    switch (command) {
      case "serve" -> {
        JsonObject arm = new JsonObject(body);
        JsonArray chunks = arm.getJsonArray("chunks");
        Map<String, String> headers = new LinkedHashMap<>();
        JsonObject armed = arm.getJsonObject("headers");
        if (armed != null) {
          armed.forEach(entry -> headers.put(entry.getKey(), String.valueOf(entry.getValue())));
        }
        answers.put(
            arm.getString("path"),
            new Answer(
                arm.getInteger("status", 200),
                arm.getString("contentType"),
                arm.getString("body"),
                headers,
                chunks == null ? null : chunks.stream().map(String::valueOf).toList(),
                arm.getLong("gapMillis", 0L),
                arm.getString("frame")));
        request.response().end();
      }
      case "dropping" -> {
        dropping = new JsonObject(body).getBoolean("value", false);
        request.response().end();
      }
      case "recording" -> {
        JsonArray lines = new JsonArray();
        // A copy under the list's own monitor: the launched process may be appending while this
        // renders, and a half-written view would shape half an edge.
        synchronized (recording) {
          recording.forEach(lines::add);
        }
        request.response().putHeader("Content-Type", "application/json").end(lines.encode());
      }
      default -> request.response().setStatusCode(404).end();
    }
  }

  // --- the client half ---------------------------------------------------------------------------

  private void control(String command, JsonObject body) {
    try {
      int status =
          http.send(
                  HttpRequest.newBuilder(URI.create(baseUrl + CONTROL + command))
                      .header("Content-Type", "application/json")
                      .POST(HttpRequest.BodyPublishers.ofString(body.encode()))
                      .build(),
                  HttpResponse.BodyHandlers.discarding())
              .statusCode();
      if (status != 200) {
        throw new IllegalStateException(name + " control " + command + " answered " + status);
      }
    } catch (Exception unreachable) {
      throw new IllegalStateException(name + " control " + command + " failed", unreachable);
    }
  }

  private String controlText(String command) {
    try {
      return http.send(
              HttpRequest.newBuilder(URI.create(baseUrl + CONTROL + command)).GET().build(),
              HttpResponse.BodyHandlers.ofString())
          .body();
    } catch (Exception unreachable) {
      throw new IllegalStateException(name + " control " + command + " failed", unreachable);
    }
  }

  /** How this service answers one path: a document, an answer written over time, or a socket. */
  private record Answer(
      int status,
      String contentType,
      String body,
      Map<String, String> headers,
      List<String> chunks,
      long gapMillis,
      String frame) {}
}
