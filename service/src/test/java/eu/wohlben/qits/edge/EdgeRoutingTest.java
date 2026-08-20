package eu.wohlben.qits.edge;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The edge end to end, against real stub upstreams on ephemeral loopback ports: two environment
 * gateways, two environments' {@code registry} and {@code mirror} applications, and a stand-in idp.
 * Only {@code mirror} is named in {@code qits.edge.auth.anonymous-read-apps}, so the gated and the
 * read-open answer are both observable from one boot.
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

  @Inject DeploymentActiveSubscriber deployments;

  @Inject EdgeRoutes routes;

  @Inject
  @DataSource("edge")
  AgroalDataSource edgeDataSource;

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

  @BeforeEach
  void publishEnvironmentFixture() throws Exception {
    clearProjection();
    for (String environment : List.of("dev", "prod")) {
      routes.replace(
          environment,
          "test-environment",
          "test-" + environment,
          Instant.EPOCH,
          List.of(
              new EdgeEndpoint(
                  environment,
                  "test-environment",
                  "/",
                  upstream("qits.test.environment-upstreams." + environment),
                  null,
                  null)));
    }
  }

  // --- the routing decision ------------------------------------------------------------------

  @Test
  void anUnclaimedEnvironmentPathIsAnAuthoritative404RatherThanGatewayTraffic() throws Exception {
    clearProjection();
    EdgeClient.Answer dev = client().get("dev.example.com", "/anything");
    EdgeClient.Answer prod = client().get("prod.example.com", "/anything");
    assertEquals(404, dev.status());
    assertEquals(404, prod.status());
    assertNull(dev.line("upstream"), "the legacy gateway must receive no traffic");
    assertNull(prod.line("upstream"), "the legacy gateway must receive no traffic");
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
  void aDeploymentActiveEndpointIsProxiedDirectlyAndNoFallbackRemains() throws Exception {
    clearProjection();
    activateArtifacts();

    assertEquals(
        "registry-dev", client().get("dev.example.com", "/artifacts/api/files").line("upstream"));
    // The route's prefix boundary matters: /artifacts catches a child, never this merely similar
    // word, which has no active endpoint and therefore has no legacy fallback.
    EdgeClient.Answer absent = client().get("dev.example.com", "/artifacts-old");
    assertEquals(404, absent.status());
    assertNull(absent.line("upstream"));
  }

  @Test
  void mainNavigationComesFromTheActiveEndpointSnapshot() {
    activateArtifacts();

    EdgeClient.Answer navigation = client().get("dev.example.com", "/main-navigation");
    assertEquals(200, navigation.status());
    assertEquals("no-store", navigation.headers().get("cache-control"));
    assertEquals(
        List.of("Home", "Artifacts"),
        new JsonObject(navigation.body())
            .getJsonArray("links").stream()
                .map(value -> ((JsonObject) value).getString("label"))
                .toList());
    assertEquals(
        List.of("/", "/artifacts/"),
        new JsonObject(navigation.body())
            .getJsonArray("links").stream()
                .map(value -> ((JsonObject) value).getString("href"))
                .toList());
  }

  @Test
  void theImmutableDefaultLeavesTheEdgeOnlyOnAHashedName() {
    activateArtifacts();

    // The SPA document: the mutable pointer naming the hashed bundles, and so the one file whose
    // staleness decides which version of an application a returning browser runs. qits-gateway
    // rewrote this and the edge did not when it replaced it, which is how a green, deployed release
    // stayed invisible for a day.
    assertEquals(
        "no-cache",
        client().get("dev.example.com", "/artifacts/spa/").headers().get("cache-control"));
    // A content-hashed name is the one place immutable is correct — a new build names a new file —
    // so this one keeps the day it was given.
    assertEquals(
        "public, immutable, max-age=86400",
        client()
            .get("dev.example.com", "/artifacts/spa/main-4RS6EA47.js")
            .headers()
            .get("cache-control"));
    // Unhashed and not the document either: a favicon replaced in place would otherwise outlive its
    // own build by a day.
    assertEquals(
        "no-cache",
        client()
            .get("dev.example.com", "/artifacts/spa/favicon.ico")
            .headers()
            .get("cache-control"));
  }

  @Test
  void aCacheHeaderTheUpstreamChoseIsNotOverruled() {
    activateArtifacts();

    // Only the untouched Quarkus default is known to be nobody's decision, and it is the only value
    // the edge may correct. no-store is somebody's decision, and the blanket rewrite this test
    // forbids would WEAKEN it.
    assertEquals(
        "no-store",
        client().get("dev.example.com", "/artifacts/spa/private").headers().get("cache-control"));
  }

  @Test
  void startupRebuildsAnEmptyProjectionFromHistoricalDeploymentsBeforeItBecomesReady()
      throws Exception {
    // A lost edge database is a real recovery path, not an empty development fixture. The
    // eventstream claim ledger may survive it, so the production bootstrap explicitly rewinds its
    // replay-from-epoch consumer and applies every application's latest historical snapshot.
    clearProjection();

    DeploymentProjectionBootstrap[] bootstrap = new DeploymentProjectionBootstrap[1];
    DeploymentProjectionCatchup historicalLog =
        new DeploymentProjectionCatchup() {
          @Override
          public eu.wohlben.qits.eventstream.control.CatchupResult rebuildFromEpoch(
              String consumerId) {
            assertEquals(DeploymentActiveSubscriber.CONSUMER_ID, consumerId);
            deployments.onFrame(
                deployment(
                    "qits-artifacts",
                    "dev",
                    "history-artifacts",
                    upstream("qits.edge.apps.registry.hosts.dev"),
                    "/history-artifacts",
                    "Artifacts"));
            deployments.onFrame(
                deployment(
                    "qits-workspaces",
                    "dev",
                    "history-workspaces",
                    upstream("qits.edge.apps.mirror.hosts.dev"),
                    "/history-workspaces",
                    "Workspaces"));
            assertFalse(
                bootstrap[0].authoritative(),
                "the snapshots must commit before the edge admits their routes");
            return new eu.wohlben.qits.eventstream.control.CatchupResult(
                consumerId,
                eu.wohlben.qits.eventstream.control.CatchupResult.Status.REACHED_HEAD,
                2);
          }

          @Override
          public eu.wohlben.qits.eventstream.control.CatchupResult catchUp(String consumerId) {
            throw new AssertionError("a confirmed head must not need a retry");
          }
        };
    bootstrap[0] =
        new DeploymentProjectionBootstrap(historicalLog, true, java.time.Duration.ofMillis(1));

    bootstrap[0].catchUpUntilReady();

    assertTrue(bootstrap[0].authoritative());
    assertNotNull(routes.resolve("dev", "/history-artifacts/api/files"));
    assertNotNull(routes.resolve("dev", "/history-workspaces/42"));
    assertEquals(
        List.of("Home", "Artifacts", "Workspaces"),
        routes.navigation("dev").stream().map(NavigationRoute.Link::label).toList());
  }

  private void clearProjection() throws java.sql.SQLException {
    try (java.sql.Connection connection = edgeDataSource.getConnection();
        java.sql.PreparedStatement endpoints =
            connection.prepareStatement("delete from edge_endpoint");
        java.sql.PreparedStatement snapshots =
            connection.prepareStatement("delete from edge_deployment_snapshot")) {
      endpoints.executeUpdate();
      snapshots.executeUpdate();
    }
    routes.load(null);
  }

  private static Upstream upstream(String property) {
    return Upstream.parse(ConfigProvider.getConfig().getValue(property, String.class), 8080);
  }

  private static eu.wohlben.qits.eventstream.control.EventFrame deployment(
      String application,
      String environment,
      String eventId,
      Upstream upstream,
      String path,
      String label) {
    return new eu.wohlben.qits.eventstream.control.EventFrame(
        eventId,
        "DeploymentActive",
        Instant.now(),
        new JsonObject()
            .put("applicationName", application)
            .put("environmentName", environment)
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(
                        new JsonObject()
                            .put("path", path)
                            .put("upstreamHost", upstream.host())
                            .put("upstreamPort", upstream.port())
                            .put("navigationLabel", label)
                            .put("navigationPosition", 1)))
            .encode(),
        null,
        null);
  }

  @Test
  void anExplicitlyEmptySnapshotRemovesThePredecessorsRoutes() {
    activateArtifacts();
    deployments.onFrame(
        new eu.wohlben.qits.eventstream.control.EventFrame(
            java.util.UUID.randomUUID().toString(),
            "DeploymentActive",
            Instant.now(),
            new JsonObject()
                .put("applicationName", "qits-artifacts")
                .put("environmentName", "dev")
                .put("endpoints", new io.vertx.core.json.JsonArray())
                .encode(),
            null,
            null));

    assertEquals("dev", client().get("dev.example.com", "/artifacts/api/files").line("upstream"));
  }

  private void activateArtifacts() {
    String configured =
        ConfigProvider.getConfig().getValue("qits.edge.apps.registry.hosts.dev", String.class);
    Upstream upstream = Upstream.parse(configured, 8080);
    String payload =
        new JsonObject()
            .put("applicationName", "qits-artifacts")
            .put("environmentName", "dev")
            .put(
                "endpoints",
                new io.vertx.core.json.JsonArray()
                    .add(
                        new JsonObject()
                            .put("path", "/artifacts")
                            .put("upstreamHost", upstream.host())
                            .put("upstreamPort", upstream.port())
                            .put("navigationLabel", "Artifacts")
                            .put("navigationPosition", 3))
                    .add(
                        new JsonObject()
                            .put("path", "/v2")
                            .put("upstreamHost", upstream.host())
                            .put("upstreamPort", upstream.port())))
            .encode();
    deployments.onFrame(
        new eu.wohlben.qits.eventstream.control.EventFrame(
            java.util.UUID.randomUUID().toString(),
            "DeploymentActive",
            Instant.now(),
            payload,
            null,
            null));
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
  void aGatedRefusalOffersBearerAndThenBasic() {
    // Two clients, two schemes, and the ORDER is the contract:
    //   * docker and containerd walk the challenges and act on the first they know, so Bearer must
    //     come first or the token flow stops being used;
    //   * maven's resolver only spends its configured credentials against a scheme it implements,
    //     so without the Basic line every uncached resolve in a build dies 401 with the right
    //     credentials sitting unused.
    // Asserted from the raw header list: a map collapses the two into one and proves nothing.
    EdgeClient.Answer answer = client().get("registry.dev.example.com", "/v2/");
    assertEquals(401, answer.status());
    assertEquals(
        List.of(
            "Bearer realm=\"http://registry.dev.example.com/token\",service=\"registry.dev.example.com\"",
            "Basic realm=\"registry.dev.example.com\""),
        answer.headerValues("www-authenticate"));
  }

  @Test
  void everyGatedRefusalCarriesBothChallengesAndNotJustTheAnonymousOne() {
    // A build resolves through both: the first request of a session carries nothing, and a later
    // one may carry a credential this vhost refuses. Both have to tell maven that Basic is taken.
    for (Map<String, String> credential :
        List.of(
            Map.<String, String>of(), basic(StubGateways.OTHER_ID, StubGateways.OTHER_SECRET))) {
      EdgeClient.Answer answer =
          client().send(HttpMethod.PUT, "registry.dev.example.com", "/v2/blob", "x", credential);
      assertEquals(401, answer.status());
      List<String> challenges = answer.headerValues("www-authenticate");
      assertEquals(2, challenges.size(), challenges.toString());
      assertTrue(challenges.get(0).startsWith("Bearer realm="), challenges.toString());
      assertEquals("Basic realm=\"registry.dev.example.com\"", challenges.get(1));
    }
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

  // --- the browser gate, dark
  // ---------------------------------------------------------------------

  @Test
  void aSessionCookieChangesNothingWhileTheGateIsOff() throws Exception {
    // qits.edge.sessions.enabled is off in this suite, which is the shipped default, and off has to
    // mean the edge of before it existed: no introspection, no redirect, no stripping. The whole
    // rest of this class is the other half of that claim — it is unchanged.
    int before = StubGateways.introspections();
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "dev.example.com",
                "/api/projects",
                null,
                Map.of(
                    "Cookie", "qits-session=" + StubGateways.SESSION,
                    "X-Qits-User", "whoever",
                    "X-Qits-Roles", "qits:root",
                    "Sec-Fetch-Mode", "navigate"));

    assertEquals(200, answer.status(), "a navigation is not redirected while the gate is off");
    assertEquals("dev", answer.line("upstream"));
    assertEquals("whoever", answer.upstreamHeader("X-Qits-User"), "nothing is stripped either");
    assertEquals("qits:root", answer.upstreamHeader("X-Qits-Roles"));
    assertEquals(before, StubGateways.introspections(), "and idp was never asked");
  }

  @Test
  void aWebSocketUpgradeIsNotGatedEitherWhileTheGateIsOff() {
    String seen =
        client().handshake("dev.example.com", "/terminal", Map.of("X-Qits-User", "whoever"));
    assertTrue(seen.lines().anyMatch("x-qits-user=whoever"::equals), seen);
  }

  // --- the anonymous-read exemption, per app ----------------------------------------------------

  @Test
  void anExemptedAppVhostServesAnAnonymousGet() {
    // `mirror` is named in qits.edge.auth.anonymous-read-apps. A pull with no credential is the
    // bootstrap case this exists for, and it has to reach the upstream rather than the challenge.
    assertEquals("mirror-dev", client().get("mirror.dev.example.com", "/v2/").line("upstream"));
    assertEquals("mirror-prod", client().get("mirror.prod.example.com", "/v2/").line("upstream"));
  }

  @Test
  void anExemptedAppVhostServesAnAnonymousHead() {
    // The other reading method, and docker uses it for every blob it checks before pulling. A HEAD
    // answer carries no body, so the upstream marker is read from the header the stub also sets.
    EdgeClient.Answer answer =
        client().send(HttpMethod.HEAD, "mirror.dev.example.com", "/v2/blob", null, Map.of());
    assertEquals(200, answer.status());
    assertEquals("mirror-dev", answer.headers().get("x-upstream"));
  }

  @Test
  void anExemptedAppVhostStillGatesEveryWritingMethod() {
    // The exemption opens READS, never a service. A push is what changes what the platform will
    // run, and it gets the same challenge as before — including the realm docker needs to act on
    // it.
    for (HttpMethod method :
        new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
      EdgeClient.Answer answer =
          client().send(method, "mirror.dev.example.com", "/v2/blob", "x", Map.of());
      assertEquals(401, answer.status(), method + " must still be gated");
      assertEquals(
          "Bearer realm=\"http://mirror.dev.example.com/token\",service=\"mirror.dev.example.com\"",
          answer.headers().get("www-authenticate"));
      assertNull(answer.line("upstream"), method + " must not have reached the application");
    }
  }

  @Test
  void anAuthenticatedWriteOnAnExemptedAppVhostPasses() {
    // The other half: the exemption is a way past the gate, not a replacement for it.
    EdgeClient.Answer answer =
        client().send(HttpMethod.POST, "mirror.dev.example.com", "/v2/blob", "x", token("dev"));
    assertEquals("mirror-dev", answer.line("upstream"));
    assertEquals("POST", answer.line("method"));
    assertEquals("x", answer.line("body"));
  }

  @Test
  void aMachineVhostNeverReceivesTheBrowserSessionCookieButKeepsOtherCookies() {
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.example.com",
                "/v2/",
                Map.of(
                    "Authorization",
                    token("dev").get("Authorization"),
                    "Cookie",
                    "theme=dark; qits-session=" + StubGateways.SESSION + "; locale=en"));
    assertEquals("registry-dev", answer.line("upstream"));
    assertEquals("theme=dark; locale=en", answer.upstreamHeader("Cookie"));
  }

  @Test
  void anAppThatWasNotNamedStillRefusesAnAnonymousRead() {
    // Per app label: `registry` is not on the list, so its reads are gated exactly as before.
    assertEquals(401, client().get("registry.dev.example.com", "/v2/").status());
    assertEquals(
        401,
        client()
            .send(HttpMethod.HEAD, "registry.dev.example.com", "/v2/", null, Map.of())
            .status());
  }

  @Test
  void anUnknownAppLabelIsStill404EvenWhereReadsAreOpen() {
    // The exemption is applied AFTER the label resolves, so it cannot turn a typo into a route.
    // `mirro` is one letter from an app whose reads are open and is still nobody's name.
    assertEquals(404, client().get("mirro.dev.example.com", "/v2/").status());
  }

  // --- the docker token endpoint ----------------------------------------------------------------

  @Test
  void theTokenEndpointAsksForTheStoredLoginCredential() {
    EdgeClient.Answer answer = client().get("registry.dev.example.com", "/token?service=x&scope=y");
    assertEquals(401, answer.status());
    // Basic ALONE, and that is the difference from a gated request: this is the endpoint that SELLS
    // bearer tokens, so a Bearer challenge here would point a client back at where it already is.
    assertEquals(
        List.of("Basic realm=\"registry.dev.example.com\""),
        answer.headerValues("www-authenticate"));
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

  // --- HTTP Basic, for the clients that cannot do docker's dance --------------------------------

  @Test
  void aClientIdAndSecretOpenAGatedVhostOnTheirOwn() {
    // maven, npm and git send Basic and nothing else. The edge spends the credential at idp and
    // reads the token that comes back, so one commissioned client works for all three.
    assertEquals(
        "registry-dev",
        client()
            .get(
                "registry.dev.example.com",
                "/v2/",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET))
            .line("upstream"));
    EdgeClient.Answer written =
        client()
            .send(
                HttpMethod.POST,
                "registry.dev.example.com",
                "/v2/blob",
                "x",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals("registry-dev", written.line("upstream"), "a write is the same decision");
    assertEquals("x", written.line("body"));
  }

  @Test
  void aBasicCredentialCarriesTheSameAudienceDemandAsABearer() {
    // The whole point of validating rather than trusting: the client is real, its secret is right,
    // and it is commissioned for an audience this vhost does not demand.
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.example.com",
                "/v2/",
                basic(StubGateways.OTHER_ID, StubGateways.OTHER_SECRET));
    assertEquals(401, answer.status());
    assertTrue(
        answer.headers().get("www-authenticate").startsWith("Bearer realm="),
        // The challenge stays docker's, whatever the credential was: docker is the client that
        // reads it, and the one that sent Basic here does not read challenges at all.
        answer.headers().get("www-authenticate"));
    assertTrue(
        answer.headers().get("www-authenticate").contains("error=\"invalid_token\""),
        "a credential that was refused says so, unlike a request that carried none");
    assertNull(answer.line("upstream"));
  }

  @Test
  void aWrongSecretIsRefusedAndIsNotRememberedAsARefusal() {
    // Refusals are not cached: a rotated secret must start working the moment it is right, rather
    // than staying shut for as long as a cache says it was wrong.
    int before = StubGateways.grants();
    assertEquals(
        401, client().get("registry.dev.example.com", "/v2/", basic("nobody", "nothing")).status());
    assertEquals(
        401, client().get("registry.dev.example.com", "/v2/", basic("nobody", "nothing")).status());
    assertEquals(before + 2, StubGateways.grants(), "each attempt is idp's decision to make");
  }

  @Test
  void aValidatedCredentialIsRememberedForATimeAndThenAskedAboutAgain() throws Exception {
    // A Basic client resends its credential on EVERY request — that is what makes it a Basic
    // client — so without a cache each dependency fetch would put an idp round trip on the path.
    Thread.sleep(cacheTtlMs() + 400);
    int before = StubGateways.grants();
    assertEquals(
        "registry-dev",
        client()
            .get(
                "registry.dev.example.com",
                "/v2/",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET))
            .line("upstream"));
    assertEquals(before + 1, StubGateways.grants(), "the first request spends the credential");

    client()
        .get(
            "registry.dev.example.com",
            "/v2/",
            basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    client()
        .get(
            "registry.prod.example.com",
            "/v2/",
            basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals(
        before + 1,
        StubGateways.grants(),
        "a remembered credential asks nobody — including on the other tier's vhost");

    Thread.sleep(cacheTtlMs() + 400);
    client()
        .get(
            "registry.dev.example.com",
            "/v2/",
            basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
    assertEquals(before + 2, StubGateways.grants(), "and the belief runs out");
  }

  @Test
  void aBasicHeaderThatIsNotACredentialIsRefusedWithoutTroublingIdp() {
    // An empty credential store, a truncated helper answer. There is nothing to ask about, and
    // asking would hold the caller for the whole patience window while idp is being waited out.
    int before = StubGateways.grants();
    assertEquals(
        401,
        client()
            .get("registry.dev.example.com", "/v2/", Map.of("Authorization", "Basic !!not-base64"))
            .status());
    assertEquals(
        401,
        client()
            .get("registry.dev.example.com", "/v2/", Map.of("Authorization", "Basic "))
            .status());
    assertEquals(before, StubGateways.grants(), "neither reached the identity provider");
  }

  @Test
  void anOpenReadStaysOpenWhateverTheCredentialSays() {
    // The exemption is decided before any credential is read, so a garbage one cannot close a door
    // that is meant to be open — the same as it has always been for a garbage Bearer.
    assertEquals(
        "mirror-dev",
        client()
            .get("mirror.dev.example.com", "/v2/", Map.of("Authorization", "Basic !!not-base64"))
            .line("upstream"));
  }

  // --- an identity provider that is not there ---------------------------------------------------

  @Test
  void theBrokerWaitsOutAnIdpThatIsComingBack() throws Exception {
    // 2026-08-14: a deploy push died with "the identity provider could not be reached" because idp
    // was a few seconds into a redeploy. A refused connection is not an answer, so it is retried.
    StubGateways.idpDown();
    try {
      java.util.concurrent.CompletableFuture<EdgeClient.Answer> answer =
          client()
              .sending(
                  HttpMethod.GET,
                  "registry.dev.example.com",
                  "/token?service=registry.dev.example.com",
                  null,
                  basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET));
      Thread.sleep(400);
      StubGateways.idpUp();
      EdgeClient.Answer issued = answer.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(200, issued.status(), issued.body());
      assertNotNull(new JsonObject(issued.body()).getString("token"));
    } finally {
      StubGateways.idpUp();
    }
  }

  @Test
  void anIdpThatAcceptsAndNeverAnswersStillEndsInAnAnswerHere() {
    // THE HANG, and the only path in this process that could produce one: a Vert.x client is built
    // with no request timeout, so a connection that is accepted and never answered leaves the
    // caller with no status, no body and nothing to time out against. docker has no timeout of its
    // own on a realm call, so it waits for as long as the socket lives.
    long start = System.currentTimeMillis();
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.example.com",
                "/token?service=registry.dev.example.com",
                basic(StubGateways.SINKHOLE_ID, StubGateways.SINKHOLE_SECRET));
    long took = System.currentTimeMillis() - start;
    assertEquals(502, answer.status());
    assertTrue(answer.body().contains("UNAVAILABLE"), answer.body());
    assertTrue(took < 25_000, "the window bounds it, and it took " + took + "ms");
  }

  @Test
  void aBasicRequestAgainstASilentIdpIsDeniedRatherThanHeld() {
    // The same certainty on the gate: a check that cannot be made denies, and it denies in bounded
    // time. An open-ended wait here would hold the connection instead of answering it.
    long start = System.currentTimeMillis();
    EdgeClient.Answer answer =
        client()
            .get(
                "registry.dev.example.com",
                "/v2/",
                basic(StubGateways.SINKHOLE_ID, StubGateways.SINKHOLE_SECRET));
    long took = System.currentTimeMillis() - start;
    assertEquals(401, answer.status());
    assertNull(answer.line("upstream"));
    assertTrue(took < 25_000, "the window bounds it, and it took " + took + "ms");
  }

  // --- the token endpoint's own credential-less arms ---------------------------------------------

  @Test
  void everyShapeOfMissingCredentialIsAnsweredPromptlyAndWhole() {
    // What docker does after the challenge is call the realm, and with nothing stored it calls it
    // with no credential or an empty one. Each of these must be a COMPLETE response — a body, a
    // length, an end — because the client that gets it is waiting with no timeout of its own.
    for (Map<String, String> headers :
        List.of(
            Map.<String, String>of(),
            Map.of("Authorization", "Basic"),
            Map.of("Authorization", "Basic "),
            Map.of(
                "Authorization",
                "Basic "
                    + Base64.getEncoder().encodeToString(":".getBytes(StandardCharsets.UTF_8))),
            Map.of("Authorization", "Basic !!not-base64"))) {
      for (HttpMethod method : new HttpMethod[] {HttpMethod.GET, HttpMethod.POST}) {
        long start = System.currentTimeMillis();
        EdgeClient.Answer answer =
            client()
                .send(
                    method,
                    "registry.dev.example.com",
                    "/token?service=registry.dev.example.com",
                    method == HttpMethod.POST ? "grant_type=client_credentials" : null,
                    headers);
        assertEquals(401, answer.status(), method + " " + headers);
        assertTrue(
            answer.headers().get("www-authenticate").startsWith("Basic realm="),
            answer.headers().get("www-authenticate"));
        assertTrue(answer.body().contains("UNAUTHORIZED"), answer.body());
        assertTrue(
            System.currentTimeMillis() - start < 5_000, method + " " + headers + " was not prompt");
      }
    }
  }

  // --- helpers -----------------------------------------------------------------------------------

  /**
   * {@code qits.edge.auth.basic-cache-ttl-ms}, which StubGateways shrinks to a suite's patience.
   */
  private static long cacheTtlMs() {
    return ConfigProvider.getConfig().getValue("qits.edge.auth.basic-cache-ttl-ms", Long.class);
  }

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
