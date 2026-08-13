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
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * The edge end to end, against real stub upstreams on ephemeral loopback ports: two environment
 * gateways, two environments' {@code registry} application, and a stand-in idp.
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
  void anApplicationSubdomainReachesThatEnvironmentsApplication() {
    // The WP1 decision in one line: the app label picks the upstream, the env label picks whose.
    assertEquals(
        "registry-dev",
        client().get("registry.dev.example.com", "/v2/", token("dev")).line("upstream"));
    assertEquals(
        "registry-prod",
        client().get("registry.prod.example.com", "/v2/", token("prod")).line("upstream"));
  }

  @Test
  void anUnconfiguredApplicationLabelIsRefusedRatherThanSentToTheGateway() {
    // NOT a fall-through. The name was aimed at a service, and the gateway is the hop that does not
    // authenticate these — a mistyped registry vhost reaching it would be an open door with a typo
    // for a key.
    EdgeClient.Answer answer = client().get("registy.dev.example.com", "/v2/");
    assertEquals(404, answer.status());
    assertTrue(answer.body().contains("registy"), answer.body());
    assertNull(answer.line("upstream"), "it must not have reached any upstream");
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
    String seen = client().get("dev.example.com", "/thing").upstreamHeader("Host");
    assertTrue(
        seen != null && seen.startsWith("dev.example.com"),
        "the upstream must see the name the client asked for, but saw: " + seen);
  }

  @Test
  void aResponseHeaderReachesTheClientUnchanged() {
    assertEquals("dev", client().get("dev.example.com", "/thing").headers().get("x-upstream"));
  }

  // --- the forwarded headers -----------------------------------------------------------------

  @Test
  void theEdgeDescribesTheOriginalClient() {
    EdgeClient.Answer answer = client().get("dev.example.com", "/thing");
    assertEquals("127.0.0.1", answer.upstreamHeader("X-Forwarded-For"));
    assertEquals("http", answer.upstreamHeader("X-Forwarded-Proto"));
    assertTrue(answer.upstreamHeader("X-Forwarded-Host").startsWith("dev.example.com"));
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
    String seen = client().handshake("dev.example.com", "/terminal", Map.of());
    assertTrue(seen.lines().anyMatch("x-forwarded-for=127.0.0.1"::equals), seen);
    assertTrue(seen.lines().anyMatch("x-forwarded-proto=http"::equals), seen);
    assertTrue(seen.lines().anyMatch(l -> l.startsWith("x-forwarded-host=dev.example.com")), seen);
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

  // --- idp auth, terminated here ---------------------------------------------------------------

  @Test
  void anApplicationVhostRefusesAnAnonymousCallerWithTheDockerChallenge() {
    // The exact string docker parses to find its token endpoint. Getting it wrong fails the pull
    // with no message anywhere, which is why it is asserted whole rather than by substring.
    EdgeClient.Answer answer = client().get("registry.dev.example.com", "/v2/");
    assertEquals(401, answer.status());
    assertEquals(
        "Bearer realm=\"http://registry.dev.example.com/token\",service=\"registry.dev.example.com\"",
        answer.headers().get("www-authenticate"));
    assertTrue(answer.body().contains("UNAUTHORIZED"), answer.body());
    assertNull(answer.line("upstream"), "an anonymous request must not reach the application");
  }

  @Test
  void anApplicationVhostRefusesATokenSignedBySomebodyElse() {
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.example.com",
                "/v2/",
                bearer(
                    TestTokens.mint(
                        TestTokens.IMPOSTOR,
                        TestTokens.KID,
                        "RS256",
                        TestTokens.claims(
                            issuer(),
                            List.of(StubGateways.audience("dev")),
                            Instant.now().plusSeconds(300)))));
    assertEquals(401, answer.status());
    // `error` is what tells docker the credential it holds is dead, so it re-fetches rather than
    // giving up. It is absent from the anonymous challenge above, on purpose.
    assertTrue(
        answer.headers().get("www-authenticate").contains("error=\"invalid_token\""),
        answer.headers().get("www-authenticate"));
    assertNull(answer.line("upstream"));
  }

  @Test
  void anApplicationVhostRefusesAnExpiredTokenAndOneForAnotherAudience() {
    assertEquals(
        401,
        client()
            .get(
                "registry.dev.example.com",
                "/v2/",
                bearer(
                    TestTokens.mint(
                        TestTokens.IDP,
                        TestTokens.KID,
                        "RS256",
                        TestTokens.claims(
                            issuer(),
                            List.of(StubGateways.audience("dev")),
                            Instant.now().minusSeconds(3600)))))
            .status());
    assertEquals(
        401,
        client()
            .get(
                "registry.dev.example.com",
                "/v2/",
                bearer(
                    TestTokens.mint(
                        TestTokens.IDP,
                        TestTokens.KID,
                        "RS256",
                        TestTokens.claims(
                            issuer(), List.of("somebody-else"), Instant.now().plusSeconds(300)))))
            .status());
  }

  @Test
  void aTokenForOneEnvironmentDoesNotUnlockAnother() {
    // The audience the edge demands is derived per request, from the environment the vhost named —
    // so dev's registry token is refused at prod's registry, and the reverse, from ONE config
    // entry.
    // Without the derivation both would pass, and the tiers would share a key.
    assertEquals(
        "registry-dev",
        client().get("registry.dev.example.com", "/v2/", token("dev")).line("upstream"));
    assertEquals(401, client().get("registry.prod.example.com", "/v2/", token("dev")).status());
    assertEquals(401, client().get("registry.dev.example.com", "/v2/", token("prod")).status());
  }

  @Test
  void aTokenNamingEveryEnvironmentsAudienceOpensEachOfThem() {
    // What idp actually mints when the grant asks for no audience: the client's whole allowed list.
    Map<String, String> whole =
        bearer(
            TestTokens.valid(
                issuer(), List.of(StubGateways.audience("dev"), StubGateways.audience("prod"))));
    assertEquals(
        "registry-dev", client().get("registry.dev.example.com", "/v2/", whole).line("upstream"));
    assertEquals(
        "registry-prod", client().get("registry.prod.example.com", "/v2/", whole).line("upstream"));
  }

  @Test
  void theEnvironmentVhostIsStillOpen() {
    // The rollout switch: qits.edge.auth.enforce-on-environments is off, so the platform's whole
    // existing traffic keeps authenticating one hop further in. Flipping it is a step of its own.
    assertEquals("dev", client().get("dev.example.com", "/anything").line("upstream"));
  }

  // --- the docker token endpoint ----------------------------------------------------------------

  @Test
  void theTokenEndpointAsksForTheStoredLoginCredential() {
    EdgeClient.Answer answer = client().get("registry.dev.example.com", "/token?service=x&scope=y");
    assertEquals(401, answer.status());
    assertTrue(
        answer.headers().get("www-authenticate").startsWith("Basic realm="),
        // Basic, not Bearer: this is the endpoint that sells bearer tokens.
        answer.headers().get("www-authenticate"));
  }

  @Test
  void theTokenEndpointBrokersAGrantAndHandsBackADockerStyleToken() {
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.example.com",
                "/token?service=registry.dev.example.com&scope=repository:qits/x:pull",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals(200, answer.status());
    JsonObject issued = new JsonObject(answer.body());
    assertNotNull(issued.getString("token"));
    assertEquals(issued.getString("token"), issued.getString("access_token"));
    assertEquals(300, issued.getInteger("expires_in"));
  }

  @Test
  void theTokenEndpointRefusesCredentialsIdpDoesNotKnow() {
    assertEquals(
        401,
        client().get("registry.dev.example.com", "/token", basic("nobody", "nothing")).status());
  }

  @Test
  void theWholeDockerFlowRoundTrips() {
    // Challenge, token, retry — the three hops a `docker pull` makes, in order, with no shortcut.
    EdgeClient.Answer challenged = client().get("registry.dev.example.com", "/v2/");
    assertEquals(401, challenged.status());
    String realm = challenged.headers().get("www-authenticate").split("realm=\"")[1].split("\"")[0];
    assertTrue(realm.endsWith("/token"), realm);

    String issued =
        new JsonObject(
                client()
                    .get(
                        "registry.dev.example.com",
                        "/token?service=registry.dev.example.com",
                        basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET))
                    .body())
            .getString("token");

    assertEquals(
        "registry-dev",
        client().get("registry.dev.example.com", "/v2/", bearer(issued)).line("upstream"));
  }

  // --- helpers -----------------------------------------------------------------------------------

  /** The issuer the stub idp uses, which is what {@code qits.idp.url} was set to. */
  private static String issuer() {
    return ConfigProvider.getConfig().getValue("qits.idp.url", String.class);
  }

  /** A token idp would mint for one environment's registry, and that environment's only. */
  private static Map<String, String> token(String environment) {
    return bearer(TestTokens.valid(issuer(), List.of(StubGateways.audience(environment))));
  }

  private static Map<String, String> bearer(String jwt) {
    return Map.of("Authorization", "Bearer " + jwt);
  }

  private static Map<String, String> basic(String id, String secret) {
    return Map.of(
        "Authorization",
        "Basic "
            + Base64.getEncoder()
                .encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8)));
  }
}
