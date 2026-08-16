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
 * <p>Everything with the flag OFF is proved by {@code EdgeRoutingTest}, which is the whole of the
 * suite as it stood before this file — that is the byte-identical claim, made by not changing it.
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
          "qits.edge.sessions.browser-hosts", "example.com,dev.example.com,prod.example.com");
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
        List.of(
            new EdgeEndpoint(
                "dev",
                "session-test-environment",
                "/",
                Upstream.parse(address, 8080),
                null,
                null)));
  }

  @AfterEach
  void unrevoke() {
    StubGateways.restore();
  }

  @AfterAll
  static void close() {
    if (client != null) {
      client.close();
      client = null;
    }
  }

  // --- the refusal, in the two shapes a caller can act on ---------------------------------------

  @Test
  void aNavigationWithNoCredentialIsSentToTheLoginPage() {
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "dev.example.com",
                "/projects/7?tab=runs",
                null,
                Map.of("Sec-Fetch-Mode", "navigate", "Accept", "text/html"));
    assertEquals(302, answer.status());
    // The whole path and query, encoded once, so the login page can put the browser back where it
    // was trying to go. Asserted character by character: a login that returns somewhere else is a
    // bug nobody files, they just re-navigate.
    assertEquals(
        "https://example.com/idp/login?return_host=dev.example.com&return_path=%2Fprojects%2F7%3Ftab%3Druns",
        answer.headers().get("location"));
    assertNull(answer.line("upstream"), "it must not have reached a gateway");
  }

  @Test
  void aNavigationIsRecognisedWithoutTheFetchModeHeaderToo() {
    // curl, an old browser, a link checker. A GET that asks for HTML is what a navigation was
    // before Sec-Fetch-Mode existed.
    EdgeClient.Answer answer =
        client()
            .get(
                "dev.example.com",
                "/",
                Map.of("Accept", "text/html,application/xhtml+xml,*/*;q=0.8"));
    assertEquals(302, answer.status());
    assertEquals(
        "https://example.com/idp/login?return_host=dev.example.com&return_path=%2F",
        answer.headers().get("location"));
  }

  @Test
  void anXhrWithNoCredentialIsRefusedRatherThanRedirected() {
    // A 302 handed to fetch() is followed into the login page's HTML, which the caller cannot use —
    // so the answer that means anything is a status. The body names the page for an SPA to send the
    // user to itself.
    EdgeClient.Answer answer =
        client()
            .get(
                "dev.example.com",
                "/api/projects",
                Map.of("Sec-Fetch-Mode", "cors", "Accept", "application/json"));
    assertEquals(401, answer.status());
    assertTrue(answer.body().contains("/idp/login"), answer.body());
    assertNull(answer.line("upstream"));
  }

  @Test
  void aRefusalOffersNoBasicChallenge() {
    // Deliberate, and the opposite of an application vhost's 401: a WWW-Authenticate naming Basic
    // pops the browser's own credential dialog on every background fetch a logged-out tab makes,
    // and the credential a browser holds is a cookie.
    assertEquals(
        List.of(),
        client()
            .get("dev.example.com", "/api/projects", Map.of("Sec-Fetch-Mode", "cors"))
            .headerValues("www-authenticate"));
  }

  @Test
  void theRedirectTargetIsOnlyEverASameOriginPath() {
    // An open redirect through the platform's own login page. `//evil.example.com` is a
    // protocol-relative URL, and the sanitiser answers `/` rather than trying to repair it.
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "dev.example.com",
                "//evil.example.com/steal",
                null,
                Map.of("Sec-Fetch-Mode", "navigate"));
    assertEquals(302, answer.status());
    assertEquals(
        "https://example.com/idp/login?return_host=dev.example.com&return_path=%2F",
        answer.headers().get("location"));
  }

  // --- a session that works ---------------------------------------------------------------------

  @Test
  void aValidSessionCookieProxiesWithTheThreeIdentityHeaders() {
    EdgeClient.Answer answer = client().get("dev.example.com", "/api/projects", session());
    assertEquals("dev", answer.line("upstream"));
    assertEquals(StubGateways.SESSION_USER, answer.upstreamHeader("X-Qits-User"));
    assertEquals(StubGateways.SESSION_USER_ID, answer.upstreamHeader("X-Qits-User-Id"));
    // Comma-separated, which is safe because a role never holds a comma.
    assertEquals("qits-platform:admin,qits:admin", answer.upstreamHeader("X-Qits-Roles"));
  }

  @Test
  void aSpoofedIdentityIsStrippedBeforeTheRealOneIsWritten() {
    // The forged header and the trusted one have the same name. This is the assertion that the
    // write happens downstream of the strip rather than beside it.
    Map<String, String> spoofed = new java.util.HashMap<>(session());
    spoofed.put("X-Qits-User", "admin");
    spoofed.put("X-Qits-User-Id", "00000000-0000-0000-0000-000000000000");
    spoofed.put("X-Qits-Roles", "qits:admin,qits:root");
    spoofed.put("x-qits-something-invented-later", "whatever");

    EdgeClient.Answer answer = client().get("dev.example.com", "/api/projects", spoofed);
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
        client().send(HttpMethod.POST, "dev.example.com", "/api/projects", "hello edge", session());
    assertEquals("POST", answer.line("method"));
    assertEquals("hello edge", answer.line("body"));
    assertEquals("10", answer.line("body-bytes"));
    assertEquals(StubGateways.SESSION_USER, answer.upstreamHeader("X-Qits-User"));
  }

  @Test
  void anAnonymousRequestCarriesNoIdentityAtAll() {
    // The other half of the strip: with nothing to assert, an upstream must see NO name rather than
    // one it cannot trust.
    EdgeClient.Answer answer =
        client().get("dev.example.com", "/idp/login", Map.of("X-Qits-User", "admin"));
    assertEquals("dev", answer.line("upstream"));
    assertNull(answer.upstreamHeader("X-Qits-User"));
  }

  @Test
  void aWebSocketUpgradeCarriesTheSessionAndDropsASpoofedIdentity() {
    // The upgrade path never reaches the interceptor chain, so it is a second way to lose both
    // halves at once — a forged X-Qits-User through the front door, or an authenticated terminal
    // arriving anonymous. Every workspace terminal on the platform is one of these.
    String seen =
        client()
            .handshake(
                "dev.example.com",
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
            .get("dev.example.com", "/terminal", Map.of("Sec-Fetch-Mode", "websocket"))
            .status());
  }

  // --- the anonymous prefix ----------------------------------------------------------------------

  @Test
  void theLoginPagesPrefixIsServedToAnybody() {
    // The one carve-out, and it is a prefix rather than an asset list: the pages need their SPA
    // files, and a list of bundle names would drift the first time one is renamed.
    assertEquals("dev", client().get("dev.example.com", "/idp/login").line("upstream"));
    assertEquals(
        "dev", client().get("dev.example.com", "/idp/assets/main-ab12cd.js").line("upstream"));
    assertEquals(
        "dev",
        client()
            .send(HttpMethod.POST, "dev.example.com", "/idp/api/auth/login", "{}", Map.of())
            .line("upstream"));
  }

  @Test
  void aDeadCookieStillReachesTheLoginPage() {
    // The loop this avoids: refuse a revoked session at /idp/login, redirect it to /idp/login, and
    // a browser that logged out can never log in again. The prefix answers every caller with no
    // usable credential, whether they carry a stale cookie or none.
    assertEquals(
        "dev",
        client()
            .get("dev.example.com", "/idp/login", cookieHeader("no-such-session"))
            .line("upstream"));
  }

  // --- machine credentials, unchanged
  // -------------------------------------------------------------

  @Test
  void aMachineBearerStillPassesOnTheEnvironmentVhost() {
    // CI dialing through the gateway. The session gate is a third acceptable credential, never a
    // replacement — and a machine's identity stays in its token, so nothing is injected for it.
    EdgeClient.Answer answer = client().get("dev.example.com", "/api/things", token("dev"));
    assertEquals("dev", answer.line("upstream"));
    assertNull(answer.upstreamHeader("X-Qits-User"), "a machine has no username to assert");
  }

  @Test
  void aMachineBasicStillPassesOnTheEnvironmentVhost() {
    // maven, npm, git — the clients that cannot do docker's dance, dialing the gateway.
    assertEquals(
        "dev",
        client()
            .get(
                "dev.example.com",
                "/api/things",
                basic(StubGateways.CLIENT_ID, StubGateways.CLIENT_SECRET))
            .line("upstream"));
  }

  @Test
  void aCredentialThatDoesNotCheckOutIsRefusedRatherThanWavedThrough() {
    // Without this, an Authorization header of any junk at all would be a way past the whole gate:
    // the environment vhost does not DEMAND a credential, so an unchecked one would open it.
    assertEquals(
        401,
        client()
            .get("dev.example.com", "/api/things", Map.of("Authorization", "Bearer not-a-token"))
            .status());
    assertEquals(
        401,
        client()
            .get(
                "dev.example.com",
                "/api/things",
                basic(StubGateways.OTHER_ID, StubGateways.OTHER_SECRET))
            .status());
  }

  // --- the application vhosts, untouched
  // ----------------------------------------------------------

  @Test
  void anApplicationVhostIsUnchangedByTheBrowserGate() {
    // Nothing browses a registry. The app vhosts keep the machine gate and nothing else — no
    // session lookup, no redirect, and no stripping either, since no service behind one reads the
    // reserved prefix.
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
    assertEquals("whoever", answer.upstreamHeader("X-Qits-User"));
  }

  @Test
  void anAnonymousReadOnAnExemptedAppVhostStillPasses() {
    assertEquals("mirror-dev", client().get("mirror.dev.example.com", "/v2/").line("upstream"));
  }

  // --- the cache ------------------------------------------------------------------------------

  @Test
  void aCachedSessionAsksIdpOnce() throws Exception {
    // Every request a browser makes carries the cookie, so without a cache every image, every XHR
    // and every poll would put an idp round trip on the path.
    Thread.sleep(cacheTtlMs() + 400);
    int before = StubGateways.introspections();
    assertEquals("dev", client().get("dev.example.com", "/api/one", session()).line("upstream"));
    assertEquals(before + 1, StubGateways.introspections(), "the first request asks");
    client().get("dev.example.com", "/api/two", session());
    client().get("dev.example.com", "/api/three", session());
    assertEquals(before + 1, StubGateways.introspections(), "and the next two do not");
  }

  @Test
  void aRevokedSessionDiesWithinTheCacheTtl() throws Exception {
    Map<String, String> revocable = cookieHeader(StubGateways.REVOCABLE_SESSION);
    assertEquals("dev", client().get("dev.example.com", "/api/one", revocable).line("upstream"));

    StubGateways.revoke();
    // Still believed for as long as the cache says so — the plan names this lag rather than hiding
    // it, and the TTL is what bounds it.
    Thread.sleep(cacheTtlMs() + 600);
    EdgeClient.Answer answer =
        client()
            .send(
                HttpMethod.GET,
                "dev.example.com",
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
                  "dev.example.com",
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
    assertEquals("dev", client().get("dev.example.com", "/api/one", session()).line("upstream"));
    Thread.sleep(cacheTtlMs() + 400);
    StubGateways.idpDown();
    try {
      EdgeClient.Answer answer = client().get("dev.example.com", "/api/two", session());
      assertEquals("dev", answer.line("upstream"), "the belief stands while idp is away");
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
                  "dev.example.com",
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
