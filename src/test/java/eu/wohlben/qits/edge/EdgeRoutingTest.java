package eu.wohlben.qits.edge;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.vertx.core.http.HttpMethod;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * The edge end to end, against two real stub gateways on ephemeral loopback ports.
 *
 * <p><b>Why one class rather than four.</b> A WebSocket upgrade through {@code vertx-http-proxy}
 * only survives the FIRST Quarkus start in a JVM — after a restart it silently degrades to a plain
 * proxied GET, so the handshake fails with nothing logged anywhere. It is a property of the test
 * harness, not of this code, and qits-gateway paid for finding it. A restart happens when a test
 * class needs a different configuration from the one before it, so the cheapest immunity is for
 * every {@code @QuarkusTest} here to share one: one class, one resource, one start. Splitting this
 * file is how the socket test starts failing for no visible reason.
 */
@QuarkusTest
@WithTestResource(StubGateways.class)
class EdgeRoutingTest {

  private static EdgeClient client;

  /**
   * Built on first use, not in {@code @BeforeAll}. Quarkus fills {@code RestAssured.port} in from
   * the port the server actually bound, and with {@code quarkus.http.test-port=0} that is not known
   * until it has; a client constructed in {@code @BeforeAll} reads the unset {@code -1}.
   */
  private static EdgeClient client() {
    if (client == null) {
      client = new EdgeClient(RestAssured.port);
    }
    return client;
  }

  @AfterAll
  static void close() {
    if (client != null) {
      client.close();
      client = null;
    }
  }

  // --- the routing decision ------------------------------------------------------------------

  @Test
  void anEnvironmentSubdomainReachesThatEnvironmentsGateway() {
    assertEquals("dev", client().get("dev.example.com", "/anything").line("upstream"));
    assertEquals("prod", client().get("prod.example.com", "/anything").line("upstream"));
  }

  @Test
  void anApplicationSubdomainReachesItsEnvironmentsGateway() {
    assertEquals("dev", client().get("home.dev.example.com", "/anything").line("upstream"));
  }

  @Test
  void theApexAndAnUnknownHostReachTheDefaultEnvironment() {
    assertEquals("prod", client().get("example.com", "/anything").line("upstream"));
    assertEquals("prod", client().get("staging.example.com", "/anything").line("upstream"));
    assertEquals("prod", client().get("127.0.0.1", "/anything").line("upstream"));
  }

  // --- verbatim forwarding -------------------------------------------------------------------

  @Test
  void thePathAndQueryReachTheUpstreamUnchanged() {
    // No path knowledge means no path rewriting: the environment gateway's route table is written
    // against the paths a client typed, and a prefix stripped here would break every one of them.
    assertEquals(
        "/deep/path/here?x=1&y=2",
        client().get("dev.example.com", "/deep/path/here?x=1&y=2").line("uri"));
  }

  @Test
  void aPostBodyReachesTheUpstream() {
    EdgeClient.Answer answer =
        client().send(HttpMethod.POST, "dev.example.com", "/api/thing", "hello edge", Map.of());
    assertEquals("POST", answer.line("method"));
    assertEquals("hello edge", answer.line("body"));
    assertEquals("10", answer.line("body-bytes"));
  }

  @Test
  void everyMethodPassesThrough() {
    for (HttpMethod method :
        new HttpMethod[] {HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH}) {
      assertEquals(
          method.name(),
          client().send(method, "dev.example.com", "/thing", "x", Map.of()).line("method"),
          "the edge must not have an opinion about " + method);
    }
  }

  @Test
  void headersReachTheUpstreamUntouched() {
    // The edge strips NOTHING. X-Qits-* hygiene is the environment gateway's job and is done there;
    // doing it here as well would put one contract in two repositories.
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "dev.example.com",
                "/thing",
                null,
                Map.of(
                    "Authorization", "Bearer secret",
                    "Cookie", "q_session=abc",
                    "X-Qits-User", "whoever",
                    "X-Custom", "kept"));

    assertEquals("Bearer secret", answer.upstreamHeader("Authorization"));
    assertEquals("q_session=abc", answer.upstreamHeader("Cookie"));
    assertEquals("whoever", answer.upstreamHeader("X-Qits-User"));
    assertEquals("kept", answer.upstreamHeader("X-Custom"));
  }

  @Test
  void theOriginalHostReachesTheUpstream() {
    // Load-bearing: every redirect, cookie domain and absolute URL the environment gateway builds
    // comes from this header. Rewriting it to the upstream's own name would break all three at once
    // and leave nothing in a log to say so.
    String seen = client().get("home.dev.example.com", "/thing").upstreamHeader("Host");
    assertTrue(
        seen != null && seen.startsWith("home.dev.example.com"),
        "the upstream must see the name the client asked for, but saw: " + seen);
  }

  @Test
  void aResponseHeaderReachesTheClientUnchanged() {
    assertEquals("dev", client().get("dev.example.com", "/thing").headers().get("x-upstream"));
  }

  // --- the forwarded headers -----------------------------------------------------------------

  @Test
  void theEdgeDescribesTheOriginalClient() {
    EdgeClient.Answer answer = client().get("home.dev.example.com", "/thing");
    assertEquals("127.0.0.1", answer.upstreamHeader("X-Forwarded-For"));
    assertEquals("http", answer.upstreamHeader("X-Forwarded-Proto"));
    assertTrue(answer.upstreamHeader("X-Forwarded-Host").startsWith("home.dev.example.com"));
  }

  @Test
  void anExistingForwardedHeaderIsKept() {
    // The edge is not always the outermost hop: a TLS terminator in front of it is the only thing
    // that can tell the truth about `https`, and overwriting would replace a true value with a
    // false one. Nothing downstream may make a trust decision on these three, and nothing does.
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "dev.example.com",
                "/thing",
                null,
                Map.of(
                    "X-Forwarded-For", "203.0.113.7",
                    "X-Forwarded-Proto", "https",
                    "X-Forwarded-Host", "edge.example.com"));

    assertEquals("203.0.113.7", answer.upstreamHeader("X-Forwarded-For"));
    assertEquals("https", answer.upstreamHeader("X-Forwarded-Proto"));
    assertEquals("edge.example.com", answer.upstreamHeader("X-Forwarded-Host"));
  }

  // --- streaming -----------------------------------------------------------------------------

  @Test
  void aChunkedResponseIsNotBuffered() {
    // The stub writes two chunks with a gap between them. A proxy that buffered would deliver both
    // at the end, so the FIRST chunk's arrival time is the assertion — the body alone would pass
    // either way. SSE channels and `git clone` are what this protects.
    Map.Entry<Long, String> streamed = client().stream("dev.example.com", "/stream");

    assertEquals("chunk-1\nchunk-2\n", streamed.getValue());
    assertTrue(
        streamed.getKey() < StubGateways.STREAM_GAP_MILLIS,
        "the first chunk arrived after "
            + streamed.getKey()
            + "ms, which is not before the upstream sent the second at "
            + StubGateways.STREAM_GAP_MILLIS
            + "ms — the response was buffered");
  }

  // --- websockets ----------------------------------------------------------------------------

  @Test
  void aWebSocketUpgradeReachesTheEnvironmentItsHostNames() {
    // Every interactive terminal on the platform is one of these. Getting a frame back at all is
    // what proves the handshake survived the hop.
    String seen = client().handshake("dev.example.com", "/terminal", Map.of());
    assertTrue(seen.lines().anyMatch("upstream=dev"::equals), seen);
  }

  @Test
  void aWebSocketUpgradeCarriesTheForwardedHeaders() {
    // The upgrade never reaches the interceptor chain — vertx-http-proxy short-circuits before
    // installing it — so this is a second code path with its own way of losing the headers.
    String seen = client().handshake("home.dev.example.com", "/terminal", Map.of());
    assertTrue(seen.lines().anyMatch("x-forwarded-for=127.0.0.1"::equals), seen);
    assertTrue(seen.lines().anyMatch("x-forwarded-proto=http"::equals), seen);
    assertTrue(
        seen.lines().anyMatch(l -> l.startsWith("x-forwarded-host=home.dev.example.com")), seen);
  }

  @Test
  void aWebSocketUpgradeStillCarriesTheClientsOwnHeaders() {
    // The edge strips nothing on an upgrade either. A terminal socket authenticates at the
    // environment gateway by the session cookie the browser sent, so dropping it here would leave
    // every terminal anonymous.
    String seen =
        client().handshake("dev.example.com", "/terminal", Map.of("Cookie", "q_session=abc"));
    assertTrue(seen.lines().anyMatch("cookie=q_session=abc"::equals), seen);
  }

  // --- the edge's own surface ------------------------------------------------------------------

  @Test
  void healthIsAnsweredByTheEdgeItself() {
    // /q never leaves this process, whatever the Host name says and whatever the environment list
    // holds — it is the one thing an orchestrator asks the EDGE about, not an environment behind
    // it. The upstream marker below is what proves it was not proxied: a stub gateway names itself
    // in every answer, so its absence is the assertion.
    RestAssured.given()
        .header("Host", "dev.example.com")
        .when()
        .get("/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", is("UP"))
        .body("checks.find { it.name == 'edge upstreams' }.data.default", is("prod"));
  }

  @Test
  void livenessIsAnsweredByTheEdgeItself() {
    RestAssured.when().get("/q/health/live").then().statusCode(200).body("status", is("UP"));
  }

  @Test
  void aPathThatOnlyLooksLikeTheManagementRootIsProxied() {
    // /q is the prefix, not a substring: /queue belongs to an environment gateway like any path.
    EdgeClient.Answer answer = client().get("dev.example.com", "/queue/items");
    assertEquals("dev", answer.line("upstream"));
    assertEquals("/queue/items", answer.line("uri"));
  }

  @Test
  void theEdgeItselfServesNothingElse() {
    // No SPA, no landing page, no /api. Every path that is not /q is somebody else's, which is why
    // an unconfigured environment shows up as a 502 rather than as a page.
    assertNotNull(client().get("dev.example.com", "/").line("upstream"));
    assertNull(client().get("dev.example.com", "/").line("body-bytes-not-a-key"));
  }
}
