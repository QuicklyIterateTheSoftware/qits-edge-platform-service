package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.vertx.core.http.HttpMethod;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The browser gate with {@code qits.edge.sessions.enabled} ON, against the same stub upstreams as
 * {@code EdgeRoutingTest} and the same stub idp.
 *
 * <p><b>Its own class AND its own surefire execution</b>, and both are load-bearing. The flag is a
 * boot-time configuration, so proving both of its states in one JVM is impossible — and a second
 * {@code @QuarkusTest} configuration in the SAME JVM restarts Quarkus, after which a WebSocket
 * upgrade through {@code vertx-http-proxy} silently degrades to a plain proxied GET and the socket
 * tests in whichever class ran second fail with nothing logged anywhere. A second execution forks a
 * second JVM, in which this class's application is the first start. See {@code pom.xml}, and
 * qits-gateway, which paid for finding this.
 *
 * <p>Everything with the flag OFF is proved by {@code EdgeRoutingTest}. The names under test here
 * are the service hosts: the environment vhost is the door, it serves nothing, and it therefore
 * gates nothing.
 */
@QuarkusTest
@WithTestResource(StubGateways.class)
@TestProfile(EdgeSessionGateTest.SessionsOn.class)
class EdgeSessionGateTest {

  @Inject EdgeRoutes routes;

  /**
   * The flag, and nothing else. The credential and the time bounds are {@code StubGateways}', which
   * is where the facts about this stub idp belong — so the difference between the two suites is
   * exactly the one line under test.
   */
  public static class SessionsOn implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.edge.sessions.enabled", "true",
          "qits.edge.sessions.canonical-origin", "https://example.com",
          // The wildcard is the whole reason a service's own name can hold a session: every
          // application of an environment is a browser host now, and listing them here would be a
          // second copy of the deployment's app list.
          "qits.edge.sessions.browser-hosts",
              "example.com,dev.example.com,prod.example.com,*.dev.example.com");
    }
  }

  private static EdgeClient client;

  /** Built on first use — see the note in {@code EdgeRoutingTest}. */
  private static EdgeClient client() {
    if (client == null) {
      client = new EdgeClient(RestAssured.port);
    }
    return client;
  }

  @BeforeEach
  void publishEnvironmentEndpoint() {
    String address =
        ConfigProvider.getConfig().getValue("qits.test.environment-upstreams.dev", String.class);
    routes.replace(
        "dev",
        "session-test-environment",
        "session-test-dev",
        java.time.Instant.EPOCH,
        EdgeRoutes.Snapshot.ofEndpoints(
            List.of(
                new EdgeEndpoint(
                    "dev", "session-test-environment", "/", Upstream.parse(address, 8080)))));
    // A flipped service, on a stub that names itself differently: `ci.dev.example.com` is a browser
    // host through the wildcard above, and nothing about it is configured in qits.edge.apps.
    String ci =
        ConfigProvider.getConfig().getValue("qits.edge.apps.mirror.hosts.dev", String.class);
    routes.replace(
        "dev",
        "session-test-ci",
        "session-test-ci",
        java.time.Instant.EPOCH,
        new EdgeRoutes.Snapshot(
            List.of(new EdgeEndpoint("dev", "session-test-ci", "/ci", Upstream.parse(ci, 8080))),
            "ci",
            List.of(new EdgeRoutes.NavigationEntry("services.details", "CI", 2))));
  }

  @AfterEach
  void unrevoke() {
    StubGateways.restore();
  }

  /**
   * Take the login page's own host away again, so the tests that prove the FALLBACK — the login at
   * the canonical origin — run against an environment where no deployment publishes one.
   */
  @AfterEach
  void withdrawIdpHost() {
    routes.replace(
        "dev",
        IDP,
        "session-test-idp-" + FRAME.incrementAndGet(),
        java.time.Instant.EPOCH.plusSeconds(FRAME.get()),
        EdgeRoutes.Snapshot.ofEndpoints(List.of()));
  }

  @AfterAll
  static void close() {
    if (client != null) {
      client.close();
      client = null;
    }
  }

  // --- the login page's own host ----------------------------------------------------------------

  @Test
  void aRefusedNavigationIsSentToTheHostThatOWNSTheLoginPath() {
    // The login moved off the door with every other service. The origin is read off the projection
    // — whoever owns /idp/login and publishes a host — and never from the canonical origin, which
    // is the door and stays the door.
    publishIdpHost();
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "ci.dev.example.com",
                "/runs/7",
                null,
                Map.of("Sec-Fetch-Mode", "navigate", "Accept", "text/html"));
    assertEquals(302, answer.status());
    assertEquals(
        "http://idp.dev.example.com/idp/login?return_host=ci.dev.example.com&return_path=%2Fruns%2F7",
        answer.headers().get("location"));
    assertNull(answer.line("upstream"), "it must not have reached the service");
  }

  @Test
  void theDoorGatesNothingBecauseItServesNothing() {
    // Not even the login page: the door has no path to refuse, so it 404s instead of redirecting —
    // with a session, with a machine token, and with neither.
    publishIdpHost();
    for (Map<String, String> credential :
        List.of(Map.<String, String>of(), session(), token("dev"))) {
      for (String path : List.of("/idp/login", "/ci/api/runs", "/v2/", "/git/x")) {
        EdgeClient.Answer answer =
            client().send(HttpMethod.GET, "dev.example.com", path, null, credential);
        assertEquals(404, answer.status(), path + " " + credential);
        assertNull(answer.line("upstream"), path + " must reach no upstream");
      }
    }
  }

  @Test
  void theLoginPageIsServedToAnybodyOnItsOwnHost() {
    // Without this the redirect above is a loop: the page it points at is on a service host, whose
    // gate refuses a caller with no session and sends them to the page they are already asking for.
    publishIdpHost();
    EdgeClient.Answer answer =
        client().get("idp.dev.example.com", "/idp/login", Map.of("X-Qits-User", "admin"));
    assertEquals(IDP_UPSTREAM, answer.line("upstream"));
    assertNull(answer.upstreamHeader("X-Qits-User"), "a forged identity is stripped, none written");
  }

  @Test
  void aDeadCookieReachesTheLoginPageOnItsOwnHostToo() {
    // The same loop, one step later: a browser holding a session idp has revoked must be able to
    // log in again. The refused cookie does not travel — the request is not using it.
    publishIdpHost();
    EdgeClient.Answer answer =
        client().get("idp.dev.example.com", "/idp/login", cookieHeader("no-such-session"));
    assertEquals(IDP_UPSTREAM, answer.line("upstream"));
    assertEquals("theme=dark", answer.upstreamHeader("Cookie"));
    assertNull(answer.upstreamHeader("X-Qits-User"));
  }

  @Test
  void anotherHostStillRefusesTheLoginPrefix() {
    // The prefix is anonymous on its OWNER's name, not on every name: /idp/ on ci is one service's
    // routes offered by another, and ci refuses it like any path it has no credential for.
    publishIdpHost();
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "ci.dev.example.com",
                "/idp/login",
                null,
                Map.of("Sec-Fetch-Mode", "navigate", "Accept", "text/html"));
    assertEquals(302, answer.status());
    assertEquals(
        "http://idp.dev.example.com/idp/login?return_host=ci.dev.example.com&return_path=%2Fidp%2Flogin",
        answer.headers().get("location"));
    assertNull(answer.line("upstream"), "it must not have reached a service");
  }

  // --- the refusal, in the two shapes a caller can act on ---------------------------------------

  @Test
  void aNavigationWithNoCredentialIsSentToTheLoginPage() {
    // The name a person types for one service. It is not a configured vhost and it is not the
    // door, and it is where a browser now meets the gate.
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "ci.dev.example.com",
                "/runs/7?tab=log",
                null,
                Map.of("Sec-Fetch-Mode", "navigate", "Accept", "text/html"));
    assertEquals(302, answer.status());
    // The whole path and query, encoded once, so the login page can put the browser back where it
    // was trying to go. Asserted character by character: a login that returns somewhere else is a
    // bug nobody files, they just re-navigate.
    assertEquals(
        "https://example.com/idp/login?return_host=ci.dev.example.com&return_path=%2Fruns%2F7%3Ftab%3Dlog",
        answer.headers().get("location"));
    assertNull(answer.line("upstream"), "it must not have reached the service");
  }

  @Test
  void aNavigationIsRecognisedWithoutTheFetchModeHeaderToo() {
    // curl, an old browser, a link checker. A GET that asks for HTML is what a navigation was
    // before Sec-Fetch-Mode existed.
    EdgeClient.Answer answer =
        client()
            .get(
                "ci.dev.example.com",
                "/",
                Map.of("Accept", "text/html,application/xhtml+xml,*/*;q=0.8"));
    assertEquals(302, answer.status());
    assertEquals(
        "https://example.com/idp/login?return_host=ci.dev.example.com&return_path=%2F",
        answer.headers().get("location"));
  }

  @Test
  void anXhrWithNoCredentialIsRefusedRatherThanRedirected() {
    // A 302 handed to fetch() is followed into the login page's HTML, which the caller cannot use —
    // so the answer that means anything is a status. A browser's fetch gets the JSON refusal that
    // names the login page and NO Basic challenge, which would pop the browser's own dialog.
    EdgeClient.Answer answer =
        client()
            .get(
                "ci.dev.example.com",
                "/api/runs",
                Map.of("Sec-Fetch-Mode", "cors", "Accept", "application/json"));
    assertEquals(401, answer.status());
    assertNull(answer.line("upstream"));
    assertNull(answer.headers().get("www-authenticate"), "a browser is never challenged for Basic");
    assertTrue(answer.body().contains("/idp/login"), answer.body());
  }

  @Test
  void aMachineClientWithNoCredentialStillGetsTheChallenge() {
    // No Sec-Fetch-Mode: curl, docker, git. The realm in the challenge is what docker acts on.
    EdgeClient.Answer answer =
        client().get("ci.dev.example.com", "/api/runs", Map.of("Accept", "application/json"));
    assertEquals(401, answer.status());
    assertNull(answer.line("upstream"));
    assertTrue(answer.headers().containsKey("www-authenticate"), "a machine client is challenged");
  }

  @Test
  void theRedirectTargetIsOnlyEverASameOriginPath() {
    // An open redirect through the platform's own login page. `//evil.example.com` is a
    // protocol-relative URL, and the sanitiser answers `/` rather than trying to repair it.
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "ci.dev.example.com",
                "//evil.example.com/steal",
                null,
                Map.of("Sec-Fetch-Mode", "navigate"));
    assertEquals(302, answer.status());
    assertEquals(
        "https://example.com/idp/login?return_host=ci.dev.example.com&return_path=%2F",
        answer.headers().get("location"));
  }

  // --- a session that works ---------------------------------------------------------------------

  @Test
  void aSpoofedIdentityIsStrippedBeforeTheRealOneIsWritten() {
    // The forged header and the trusted one have the same name. This is the assertion that the
    // write happens downstream of the strip rather than beside it.
    Map<String, String> spoofed = new java.util.HashMap<>(session());
    spoofed.put("X-Qits-User", "admin");
    spoofed.put("X-Qits-User-Id", "00000000-0000-0000-0000-000000000000");
    spoofed.put("X-Qits-Roles", "qits:admin,qits:root");
    spoofed.put("x-qits-something-invented-later", "whatever");

    EdgeClient.Answer answer = client().get("ci.dev.example.com", "/api/runs", spoofed);
    assertEquals(StubGateways.SESSION_USER, answer.upstreamHeader("X-Qits-User"));
    assertEquals(StubGateways.SESSION_USER_ID, answer.upstreamHeader("X-Qits-User-Id"));
    assertEquals("qits-platform:admin,qits:admin", answer.upstreamHeader("X-Qits-Roles"));
    assertNull(
        answer.upstreamHeader("X-Qits-Something-Invented-Later"),
        "the prefix is the rule, so a header nobody has thought of yet is stripped too");
  }

  @Test
  void aWriteWaitingOnIdpKeepsItsBody() throws Exception {
    // The introspection crosses an event-loop boundary, so the inbound request is PAUSED until
    // there is somewhere to send it. Get that wrong and every write made from a browser arrives
    // upstream empty. The sleep is what guarantees the call happens rather than a cache hit.
    Thread.sleep(cacheTtlMs() + 400);
    EdgeClient.Answer answer =
        client().send(HttpMethod.POST, "ci.dev.example.com", "/api/runs", "hello edge", session());
    assertEquals("POST", answer.line("method"));
    assertEquals("hello edge", answer.line("body"));
    assertEquals("10", answer.line("body-bytes"));
    assertEquals(StubGateways.SESSION_USER, answer.upstreamHeader("X-Qits-User"));
  }

  @Test
  void aWebSocketUpgradeCarriesTheSessionAndDropsASpoofedIdentity() {
    // The upgrade path never reaches the interceptor chain, so it is a second way to lose both
    // halves at once — a forged X-Qits-User through the front door, or an authenticated terminal
    // arriving anonymous. Every workspace terminal on the platform is one of these.
    String seen =
        client()
            .handshake(
                "ci.dev.example.com",
                "/terminal",
                Map.of(
                    "Cookie",
                    cookie(StubGateways.SESSION),
                    "X-Qits-User",
                    "admin",
                    "X-Qits-Roles",
                    "qits:root"));
    assertTrue(seen.lines().anyMatch(("x-qits-user=" + StubGateways.SESSION_USER)::equals), seen);
    assertTrue(
        seen.lines().anyMatch(("x-qits-user-id=" + StubGateways.SESSION_USER_ID)::equals), seen);
    assertTrue(seen.lines().anyMatch("x-qits-roles=qits-platform:admin,qits:admin"::equals), seen);
  }

  @Test
  void aWebSocketUpgradeWithNoSessionIsRefusedRatherThanRedirected() {
    // A 302 kills a handshake with nothing to follow it, so the socket has to be told no. The
    // browser says which kind of fetch this is, and `websocket` is not a navigation.
    assertEquals(
        401,
        client()
            .get("ci.dev.example.com", "/terminal", Map.of("Sec-Fetch-Mode", "websocket"))
            .status());
  }

  // --- the anonymous prefix, on the host that owns it --------------------------------------------

  @Test
  void theLoginPagesPrefixIsServedToAnybody() {
    // The one carve-out, and it is a prefix rather than an asset list: the pages need their SPA
    // files, and a list of bundle names would drift the first time one is renamed.
    publishIdpHost();
    assertEquals(IDP_UPSTREAM, client().get("idp.dev.example.com", "/idp/login").line("upstream"));
    assertEquals(
        IDP_UPSTREAM,
        client().get("idp.dev.example.com", "/idp/assets/main-ab12cd.js").line("upstream"));
    assertEquals(
        IDP_UPSTREAM,
        client()
            .send(HttpMethod.POST, "idp.dev.example.com", "/idp/api/auth/login", "{}", Map.of())
            .line("upstream"));
  }

  // --- machine credentials, unchanged
  // -------------------------------------------------------------

  @Test
  void aMachineBasicStillPassesOnAServiceHost() {
    // maven, npm, git — the clients that cannot do docker's dance, dialing a service by its name.
    assertEquals(
        "mirror-dev",
        client()
            .get(
                "ci.dev.example.com",
                "/api/runs",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET))
            .line("upstream"));
  }

  @Test
  void aCredentialThatDoesNotCheckOutIsRefusedRatherThanWavedThrough() {
    // Without this, an Authorization header of any junk at all would be a way past the whole gate:
    // it suppresses the cookie lookup, so an unchecked one would open the name.
    assertEquals(
        401,
        client()
            .get("ci.dev.example.com", "/api/runs", Map.of("Authorization", "Bearer not-a-token"))
            .status());
    assertEquals(
        401,
        client()
            .get(
                "ci.dev.example.com",
                "/api/runs",
                basic(StubGateways.OTHER_ID, StubGateways.OTHER_SECRET))
            .status());
  }

  // --- a service's own name, which is a browser host now ----------------------------------------

  @Test
  void aSessionReachesAServiceHostWithItsIdentityAndKeepsItsCookie() {
    // The cookie is the credential here, so it stays: the service behind the name is an ordinary
    // qits service and the browser will make the next request with it too. The three identity
    // headers are what it reads.
    EdgeClient.Answer answer = client().get("ci.dev.example.com", "/runs/7", session());
    assertEquals("mirror-dev", answer.line("upstream"), "its own upstream, not the environment's");
    assertEquals(StubGateways.SESSION_USER, answer.upstreamHeader("X-Qits-User"));
    assertEquals(StubGateways.SESSION_USER_ID, answer.upstreamHeader("X-Qits-User-Id"));
    assertEquals("qits-platform:admin,qits:admin", answer.upstreamHeader("X-Qits-Roles"));
    assertTrue(
        answer.upstreamHeader("Cookie").contains("qits-session="), answer.upstreamHeader("Cookie"));
  }

  @Test
  void aMachineCredentialStillOpensAServiceHost() {
    // CI dialing a service by its own name. A machine's identity is in its token, so nothing is
    // asserted for it.
    EdgeClient.Answer answer = client().get("ci.dev.example.com", "/api/runs", token("dev"));
    assertEquals("mirror-dev", answer.line("upstream"));
    assertNull(answer.upstreamHeader("X-Qits-User"));
  }

  // --- the application vhosts, untouched
  // ----------------------------------------------------------

  @Test
  void anApplicationVhostKeepsTheMachineGateAndStillStripsTheReservedNamespace() {
    // Nothing browses a registry, so the app vhosts keep the machine gate and nothing of the
    // browser
    // machinery — no session lookup, no redirect. But the reserved-prefix strip is NOT part of that
    // machinery: it runs on every path out of the edge, because it is the whole basis of a
    // forward-auth service's trust in X-Qits-User and cannot depend on there being a session here.
    // A registry does not read the prefix, but this path is shared with application vhosts whose
    // services do, and a valid machine token is not licence to forge a user identity behind it.
    assertEquals(401, client().get("registry.dev.example.com", "/v2/").status());
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "registry.dev.example.com",
                "/v2/",
                null,
                withToken("dev", "X-Qits-User", "whoever"));
    assertEquals("registry-dev", answer.line("upstream"));
    assertNull(
        answer.upstreamHeader("X-Qits-User"),
        "a client-supplied identity is stripped even on a machine vhost with a valid token");
  }

  @Test
  void anAnonymousReadOnAnExemptedAppVhostStillPasses() {
    assertEquals("mirror-dev", client().get("mirror.dev.example.com", "/v2/").line("upstream"));
  }

  @Test
  void aMachineVhostStillStripsTheBrowserCookieAndKeepsTheOthers() {
    // The parent-domain session reaches every sibling name by browser design. A request that
    // identifies itself with a token is not using it, so the registry never sees it.
    Map<String, String> headers = new java.util.HashMap<>(token("dev"));
    headers.put("Cookie", "theme=dark; qits-session=" + StubGateways.SESSION + "; locale=en");
    EdgeClient.Answer answer = client().get("registry.dev.example.com", "/v2/", headers);
    assertEquals("registry-dev", answer.line("upstream"));
    assertEquals("theme=dark; locale=en", answer.upstreamHeader("Cookie"));
  }

  // --- the cache ------------------------------------------------------------------------------

  @Test
  void aCachedSessionAsksIdpOnce() throws Exception {
    // Every request a browser makes carries the cookie, so without a cache every image, every XHR
    // and every poll would put an idp round trip on the path.
    Thread.sleep(cacheTtlMs() + 400);
    int before = StubGateways.introspections();
    assertEquals(
        "mirror-dev", client().get("ci.dev.example.com", "/api/one", session()).line("upstream"));
    assertEquals(before + 1, StubGateways.introspections(), "the first request asks");
    client().get("ci.dev.example.com", "/api/two", session());
    client().get("ci.dev.example.com", "/api/three", session());
    assertEquals(before + 1, StubGateways.introspections(), "and the next two do not");
  }

  @Test
  void aRevokedSessionDiesWithinTheCacheTtl() throws Exception {
    Map<String, String> revocable = cookieHeader(StubGateways.REVOCABLE_SESSION);
    assertEquals(
        "mirror-dev", client().get("ci.dev.example.com", "/api/one", revocable).line("upstream"));

    StubGateways.revoke();
    // Still believed for as long as the cache says so — the plan names this lag rather than hiding
    // it, and the TTL is what bounds it.
    Thread.sleep(cacheTtlMs() + 600);
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "ci.dev.example.com",
                "/api/two",
                null,
                withCookie(StubGateways.REVOCABLE_SESSION, "Sec-Fetch-Mode", "cors"));
    assertEquals(401, answer.status());
    assertNull(answer.line("upstream"));
  }

  @Test
  void anUnknownCookieIsRefusedAndIsNotRememberedAsARefusal() throws Exception {
    // Refusals are not cached: the case it would speed up is a browser that has just logged in,
    // which would then keep being refused after the login it completed.
    int before = StubGateways.introspections();
    for (int i = 0; i < 2; i++) {
      assertEquals(
          401,
          client()
              .send(
                  HttpMethod.GET,
                  "ci.dev.example.com",
                  "/api/things",
                  null,
                  withCookie("no-such-session", "Sec-Fetch-Mode", "cors"))
              .status());
    }
    assertEquals(before + 2, StubGateways.introspections(), "each attempt is idp's to decide");
  }

  @Test
  void aCachedSessionOutlivesAnIdpThatIsBeingReplaced() throws Exception {
    // The token broker's 2026-08-14 lesson, applied to people: idp is redeployed like any other
    // container, and a browser must not be logged out because it happened mid-click.
    assertEquals(
        "mirror-dev", client().get("ci.dev.example.com", "/api/one", session()).line("upstream"));
    Thread.sleep(cacheTtlMs() + 400);
    StubGateways.idpDown();
    try {
      EdgeClient.Answer answer = client().get("ci.dev.example.com", "/api/two", session());
      assertEquals("mirror-dev", answer.line("upstream"), "the belief stands while idp is away");
      assertEquals(StubGateways.SESSION_USER, answer.upstreamHeader("X-Qits-User"));
    } finally {
      StubGateways.idpUp();
    }
  }

  @Test
  void aSessionNobodyHasVouchedForIsRefusedWhileIdpIsAway() {
    // The grace extends a belief; it does not invent one. An unknown cookie during an idp outage
    // is a caller nothing is known about, and a validator that cannot answer denies.
    StubGateways.idpDown();
    try {
      assertEquals(
          401,
          client()
              .send(
                  HttpMethod.GET,
                  "ci.dev.example.com",
                  "/api/things",
                  null,
                  withCookie("another-unknown-session", "Sec-Fetch-Mode", "cors"))
              .status());
    } finally {
      StubGateways.idpUp();
    }
  }

  // --- the edge's own surface, still its own -----------------------------------------------------

  @Test
  void healthIsNeverGated() {
    // An orchestrator has no session and must never need one: a health probe that 302s to a login
    // page is a container that never comes up.
    io.restassured.RestAssured.given()
        .header("Host", "dev.example.com")
        .when()
        .get("/q/health/ready")
        .then()
        .statusCode(200);
  }

  // --- helpers -----------------------------------------------------------------------------------

  /** The application that owns {@code /idp/login} — a platform service, deployed once. */
  private static final String IDP = "qits-platform-idp";

  /** The stub standing in for it, which names itself in every answer. */
  private static final String IDP_UPSTREAM = "registry-prod";

  /** Frame order, so publishing and withdrawing the host cannot lose a race with itself. */
  private static final java.util.concurrent.atomic.AtomicLong FRAME =
      new java.util.concurrent.atomic.AtomicLong();

  /** idp as it will be deployed: the login route, and its own name to serve it from. */
  private void publishIdpHost() {
    String upstream =
        ConfigProvider.getConfig().getValue("qits.edge.apps.registry.hosts.prod", String.class);
    routes.replace(
        "dev",
        IDP,
        "session-test-idp-" + FRAME.incrementAndGet(),
        java.time.Instant.EPOCH.plusSeconds(FRAME.get()),
        new EdgeRoutes.Snapshot(
            List.of(new EdgeEndpoint("dev", IDP, "/idp", Upstream.parse(upstream, 8080))),
            "idp",
            List.of()));
  }

  private static long cacheTtlMs() {
    return ConfigProvider.getConfig().getValue("qits.edge.sessions.cache-ttl-ms", Long.class);
  }

  private static String issuer() {
    return ConfigProvider.getConfig().getValue("qits.idp.url", String.class);
  }

  private static String cookie(String value) {
    // Two cookies, because a browser sends every cookie it holds for the host and the session one
    // is rarely first.
    return "theme=dark; qits-session=" + value;
  }

  private static Map<String, String> cookieHeader(String value) {
    return Map.of("Cookie", cookie(value));
  }

  private static Map<String, String> session() {
    return cookieHeader(StubGateways.SESSION);
  }

  private static Map<String, String> withCookie(String value, String name, String header) {
    return Map.of("Cookie", cookie(value), name, header);
  }

  private static Map<String, String> withToken(
      String environment, String name, String headerValue) {
    Map<String, String> headers = new java.util.HashMap<>(token(environment));
    headers.put(name, headerValue);
    return headers;
  }

  private static Map<String, String> token(String environment) {
    return Map.of(
        "Authorization",
        "Bearer " + TestTokens.valid(issuer(), List.of(StubGateways.audience(environment))));
  }

  private static Map<String, String> basic(String id, String secret) {
    return Map.of(
        "Authorization",
        "Basic "
            + Base64.getEncoder()
                .encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8)));
  }
}
