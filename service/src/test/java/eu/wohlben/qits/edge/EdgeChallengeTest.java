package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.WithDefault;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The decisions {@code EdgeAuth} and {@code EdgeSessions} make without touching a socket: the
 * {@code WWW-Authenticate} value character by character, the audience it demands, which requests
 * skip the gate entirely, and — for the browser half — what a cookie header says, what may be
 * redirected, and where a login may return to.
 *
 * <p>The challenge is a wire contract with a client nobody here controls — docker parses it to find
 * the token endpoint, and one it cannot parse fails a pull with no message in any log on either
 * side. The browser half's contracts are the same kind of thing one hop out: a cookie name idp
 * sets, a login path its SPA serves, and a redirect target that must never leave this host.
 */
class EdgeChallengeTest {

  @Test
  void theDemandedAudienceIsTheVhostsOwnEnvironment() {
    // One entry, and the tiers cannot unlock each other. idp's audience values are env-prefixed, so
    // a fixed string would either match one environment or, if widened, all of them.
    assertEquals("dev-qits-artifacts", EdgeAuth.audienceFor("{env}-qits-artifacts", "dev"));
    assertEquals("prod-qits-artifacts", EdgeAuth.audienceFor("{env}-qits-artifacts", "prod"));
  }

  @Test
  void aPatternWithNoPlaceholderIsALiteralAudience() {
    // What a single-audience deployment configures. It must keep working unchanged.
    assertEquals("qits-registry", EdgeAuth.audienceFor("qits-registry", "dev"));
  }

  @Test
  void aDirectVhostCanDemandItsOwnAudienceWithoutChangingTheDefault() {
    EdgeConfig.App githost =
        new EdgeConfig.App() {
          @Override
          public String audiencePattern() {
            return "{env}-qits-githost";
          }

          @Override
          public String hostPattern() {
            return "{env}-qits-githost";
          }

          @Override
          public int port() {
            return 8080;
          }

          @Override
          public Map<String, String> hosts() {
            return Map.of();
          }
        };

    assertEquals(
        "dev-qits-githost",
        EdgeAuth.audienceFor(
            app("githost", "dev"), "{env}-qits-artifacts", Map.of("githost", githost)));
    assertEquals(
        "dev-qits-artifacts",
        EdgeAuth.audienceFor(
            app("registry", "dev"), "{env}-qits-artifacts", Map.of("githost", githost)));
    assertEquals(
        "prod-qits-artifacts",
        EdgeAuth.audienceFor(
            HostEnvironments.Route.apex("prod"),
            "{env}-qits-artifacts",
            Map.of("githost", githost)));
  }

  // --- the platform audience ---------------------------------------------------------------------

  @Test
  void thePlatformAudienceIsAcceptedNextToTheVhostsOwn() {
    assertEquals(
        List.of("dev-qits-artifacts", "qits-platform"),
        EdgeAuth.acceptedAudiences("dev-qits-artifacts", Optional.of("qits-platform")));
    assertEquals(
        List.of("dev-qits-artifacts"),
        EdgeAuth.acceptedAudiences("dev-qits-artifacts", Optional.of("dev-qits-artifacts")),
        "an audience that is both is named once");
  }

  @Test
  void anEmptyPlatformAudienceSwitchesTheRuleOff() {
    assertEquals(
        List.of("dev-qits-artifacts"),
        EdgeAuth.acceptedAudiences("dev-qits-artifacts", Optional.empty()));
    assertEquals(
        List.of("dev-qits-artifacts"),
        EdgeAuth.acceptedAudiences("dev-qits-artifacts", Optional.of("  ")));
  }

  @Test
  void theShippedPlatformAudienceHasNoTier() throws Exception {
    // Roles, not tiers, are the permission. A placeholder here would make it a tier again.
    assertEquals("qits-platform", shippedDefault("platformAudience"));
  }

  @Test
  void anEmptyConfiguredValueIsReadAsOff() {
    // The operator's switch: QITS_EDGE_AUTH_PLATFORM_AUDIENCE= must mean "off", not "the default".
    assertEquals(
        Optional.empty(),
        authConfig(Map.of("qits.edge.auth.platform-audience", "")).platformAudience());
    assertEquals(Optional.of("qits-platform"), authConfig(Map.of()).platformAudience());
  }

  @Test
  void aBasicCredentialFollowsTheSameAudienceRule() {
    // The Basic path judges the minted token's audiences, cached or fresh, with the same rule.
    List<String> accepted =
        EdgeAuth.acceptedAudiences("dev-qits-artifacts", Optional.of("qits-platform"));
    assertNull(EdgeAuth.refusalFor(new JsonArray(List.of("qits-platform")), accepted));
    assertNull(EdgeAuth.refusalFor(new JsonArray(List.of("dev-qits-artifacts")), accepted));
    assertEquals(
        "the credential is not for dev-qits-artifacts or qits-platform",
        EdgeAuth.refusalFor(new JsonArray(List.of("somebody-else")), accepted));
    assertEquals(
        "the credential is not for dev-qits-artifacts",
        EdgeAuth.refusalFor(
            new JsonArray(List.of("qits-platform")), List.of("dev-qits-artifacts")));
  }

  @Test
  void theChallengePointsBackAtThisSameVhostsTokenEndpoint() {
    assertEquals(
        "Bearer realm=\"http://registry.dev.localhost:8080/token\",service=\"registry.dev.localhost:8080\"",
        EdgeAuth.bearerChallenge("http", "registry.dev.localhost:8080", "no bearer token"));
  }

  @Test
  void theBasicChallengeNamesTheSameAuthorityTheBearerOneServes() {
    // One door, described twice. A realm that disagreed with the Bearer challenge's `service` would
    // read as two credentials to a client that shows the user which one it is asking for.
    assertEquals(
        "Basic realm=\"registry.dev.localhost:8080\"",
        EdgeAuth.basicChallenge("registry.dev.localhost:8080"));
  }

  @Test
  void aRejectedTokenSaysSoSoTheClientRefetchesRatherThanGivingUp() {
    assertTrue(
        EdgeAuth.bearerChallenge("https", "registry.dev.localhost", "the token expired")
            .endsWith(",error=\"invalid_token\""));
    // Absent on a first anonymous request: clients read an error there as "these credentials are
    // wrong" and stop, rather than as "you have not tried yet".
    assertFalse(
        EdgeAuth.bearerChallenge("https", "registry.dev.localhost", "no bearer token")
            .contains("error="));
  }

  @Test
  void aHostHeaderCannotWriteItsOwnRealm() {
    // The Host header is echoed into a quoted header value. Without the filter, a caller could
    // close
    // the quote and point a docker client at somebody else's token endpoint.
    assertEquals(
        "evil.example.comrealmhttp:attacker",
        EdgeAuth.safeAuthority("evil.example.com\",realm=\"http://attacker"));
    assertEquals("", EdgeAuth.safeAuthority(null));
    assertEquals(
        "registry.dev.localhost:8080", EdgeAuth.safeAuthority("registry.dev.localhost:8080"));
  }

  @Test
  void theSchemeIsCarriedIntoTheRealm() {
    assertTrue(
        EdgeAuth.bearerChallenge("https", "registry.dev.localhost", "no bearer token")
            .contains("realm=\"https://registry.dev.localhost/token\""));
  }

  // --- the anonymous-read exemption --------------------------------------------------------------

  @Test
  void anExemptedAppServesTheTwoReadingMethodsAndNoOthers() {
    // The whole shape of the decision: reads are the bootstrap steps that happen before there is a
    // token to hold, writes change what the platform will run and keep the check.
    Set<String> open = Set.of("mirror");
    assertTrue(EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.GET, open));
    assertTrue(EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.HEAD, open));
    for (HttpMethod method :
        new HttpMethod[] {
          HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS
        }) {
      assertFalse(
          EdgeAuth.anonymousRead(app("mirror", "dev"), method, open),
          method + " is not a read and must still need a token");
    }
  }

  @Test
  void theExemptionIsPerAppLabel() {
    // An app that was not named is gated on every method, which is the pre-exemption edge exactly.
    assertFalse(EdgeAuth.anonymousRead(app("registry", "dev"), HttpMethod.GET, Set.of("mirror")));
    // Every environment of a named app, from one entry — the label is the whole key.
    assertTrue(EdgeAuth.anonymousRead(app("mirror", "prod"), HttpMethod.GET, Set.of("mirror")));
  }

  @Test
  void theExemptionNeverReachesTheEnvironmentVhost() {
    // The environment vhost has its own switch, and it routes the platform's whole existing
    // traffic. No value in this list may widen it — not even one that spells an environment name.
    assertFalse(
        EdgeAuth.anonymousRead(
            HostEnvironments.Route.apex("dev"), HttpMethod.GET, Set.of("mirror", "dev")));
  }

  @Test
  void anEmptyListGatesEverything() {
    // Today's behaviour, and the shipped default: with nothing named, every method on every app
    // vhost needs a token.
    assertFalse(EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.GET, Set.of()));
    assertFalse(EdgeAuth.anonymousRead(app("registry", "dev"), HttpMethod.HEAD, Set.of()));
  }

  @Test
  void theShippedAudiencePatternsAreTheLiteralPlatformAudience() throws Exception {
    // The open calling model's own rule (service-client-identity-plan.md, C4): a freshly configured
    // vhost — the global default and an app entry with no override of its own — opens with roles
    // alone, not a tier-scoped audience. An explicitly configured pattern still wins; see
    // StubGateways, which sets one for exactly the app entries this pins.
    assertEquals("qits-platform", shippedDefault("audiencePattern"));
    assertEquals(
        "qits-platform",
        EdgeConfig.App.class.getMethod("audiencePattern").getAnnotation(WithDefault.class).value());
  }

  @Test
  void theShippedAudienceDefaultsCollapseAcceptedAudiencesToOne() {
    // Both shipped defaults are the SAME literal, so acceptedAudiences names it once rather than
    // twice — proved against the real defaults above, not a value this test chose on its own.
    assertEquals(
        List.of("qits-platform"),
        EdgeAuth.acceptedAudiences("qits-platform", Optional.of("qits-platform")));
  }

  @Test
  void theShippedDefaultNamesNoApp() throws Exception {
    // A default here would open reads on a deployment that never asked, and the names it would open
    // are exactly the ones worth closing. Pinned rather than assumed: absent means empty.
    assertNull(
        AuthConfig.class.getMethod("anonymousReadApps").getAnnotation(WithDefault.class),
        "qits.edge.auth.anonymous-read-apps must have no default");
  }

  @Test
  void aConfiguredLabelIsReadInTheSpellingAHostNameArrivesIn() {
    // Host names arrive in any case at all and HostEnvironments lower-cases what it resolves, so
    // without this normalisation `Mirror` in configuration would open nothing.
    assertEquals(Set.of("mirror", "registry"), EdgeAuth.readApps(List.of(" Mirror ", "REGISTRY")));
    assertEquals(Set.of(), EdgeAuth.readApps(List.of("", "  ")));
  }

  // --- HTTP Basic ------------------------------------------------------------------------------

  @Test
  void aCredentialIsBase64OfAClientIdAndASecret() {
    // The shape, and nothing about whether idp knows it. What this rejects never reaches idp, so a
    // client with an empty credential store is answered here rather than made to wait for one.
    assertTrue(EdgeAuth.isClientCredentials(encode("a-client:a-secret")));
    assertTrue(
        EdgeAuth.isClientCredentials(encode("a-client:a:secret")), "a secret may hold a colon");
    assertFalse(EdgeAuth.isClientCredentials(null));
    assertFalse(EdgeAuth.isClientCredentials(""));
    assertFalse(EdgeAuth.isClientCredentials("!!not-base64"));
    assertFalse(EdgeAuth.isClientCredentials(encode("no-colon-at-all")));
    assertFalse(EdgeAuth.isClientCredentials(encode(":")), "no id and no secret is neither");
    assertFalse(EdgeAuth.isClientCredentials(encode("an-id:")), "a client id alone is not one");
    assertFalse(EdgeAuth.isClientCredentials(encode(":a-secret")));
  }

  @Test
  void gitOauthBasicCarriesAnAccessTokenAsItsPassword() {
    assertEquals(
        "header.payload.signature",
        EdgeAuth.oauth2Token(encode("oauth2:header.payload.signature")));
    assertNull(EdgeAuth.oauth2Token(encode("client:secret")));
    assertNull(EdgeAuth.oauth2Token(encode("oauth2:")));
    assertNull(EdgeAuth.oauth2Token("not-base64"));
  }

  @Test
  void aCachedCredentialIsHeldAsAHashAndNeverAsItself() {
    // The cache key is a caller's SECRET. A hash is what keeps it out of a map, a log line and a
    // heap dump, and the same credential has to keep finding its own entry.
    String credential = encode("a-client:a-secret");
    String fingerprint = EdgeAuth.fingerprint(credential);
    assertEquals(fingerprint, EdgeAuth.fingerprint(credential));
    assertNotEquals(fingerprint, EdgeAuth.fingerprint(encode("a-client:another-secret")));
    assertFalse(fingerprint.contains("a-secret"));
    assertFalse(fingerprint.contains(credential));
  }

  @Test
  void aBeliefOutlivesNeitherTheCeilingNorTheTokenItHolds() {
    // What is cached beside the verdict is the token the edge will FORWARD, so the entry has to
    // die while that token is still worth forwarding — it is validated one hop further in, a
    // moment later, against another process' clock.
    long now = 1_000_000L;
    assertEquals(
        now + 60_000,
        EdgeAuth.believeUntil(now, 60_000, Instant.ofEpochMilli(now + 300_000), 60_000),
        "the configured ceiling binds while the token has life to spare");
    assertEquals(
        now + 240_000,
        EdgeAuth.believeUntil(now, 300_000, Instant.ofEpochMilli(now + 300_000), 60_000),
        "and the token's own life binds otherwise — less the margin, which is the whole point");
    assertEquals(
        now,
        EdgeAuth.believeUntil(now, 300_000, Instant.ofEpochMilli(now + 30_000), 60_000),
        "a token already inside the margin is a belief that expired before it was written, so the"
            + " credential is spent again rather than a dying token being handed on");
    assertEquals(
        now,
        EdgeAuth.believeUntil(now, 300_000, Instant.ofEpochMilli(now - 1), 60_000),
        "and never a time in the past, which a hit would read as live");
    assertEquals(
        now,
        EdgeAuth.believeUntil(now, 300_000, null, 60_000),
        "a token that names no expiry is refused before this, and is cached for no time at all");
  }

  @Test
  void theRemintMarginIsTheEstatesOwn() {
    // The same sixty seconds AgentCredential treats a token as spent at, elsewhere on the estate.
    assertEquals(60_000, EdgeAuth.TOKEN_MARGIN_MS);
  }

  // --- the patience the identity provider is given ----------------------------------------------

  @Test
  void onlyTheConnectionIsWaitedOut() {
    // An ANSWER from idp is idp deciding, and it arrives as a Grant rather than a failure — so the
    // only thing this classifies is the network, and every shape of it is safe to repeat.
    assertTrue(IdpGrants.connectionClassed(new java.net.ConnectException("Connection refused")));
    assertTrue(IdpGrants.connectionClassed(new java.net.UnknownHostException("qits-platform-idp")));
    assertTrue(IdpGrants.connectionClassed(new java.util.concurrent.TimeoutException()));
    assertTrue(
        IdpGrants.connectionClassed(
            new RuntimeException(new java.io.IOException("Connection reset by peer"))));
    // Vert.x reports these two as a plain exception with no cause, so the message is the only
    // evidence there is.
    assertTrue(IdpGrants.connectionClassed(new RuntimeException("Connection was closed")));
    assertTrue(IdpGrants.connectionClassed(new RuntimeException("The timeout period elapsed")));
    assertFalse(IdpGrants.connectionClassed(new IllegalStateException("not a network problem")));
  }

  @Test
  void theWaitBetweenTriesDoublesAndIsCapped() {
    assertEquals(IdpGrants.FIRST_BACKOFF_MS, IdpGrants.backoffMs(0));
    assertEquals(IdpGrants.FIRST_BACKOFF_MS * 2, IdpGrants.backoffMs(1));
    assertEquals(IdpGrants.FIRST_BACKOFF_MS * 4, IdpGrants.backoffMs(2));
    assertEquals(IdpGrants.BACKOFF_CAP_MS, IdpGrants.backoffMs(30), "a long window is still tries");
  }

  @Test
  void theShippedTimeBoundsAreTheDeploymentsAndNotTheSuites() throws Exception {
    // The suite shrinks all three so its own tests are quick. What a deployment gets is here, and
    // the call timeout is the one that matters most: without it there is no answer at all.
    assertEquals("5000", shippedDefault("idpCallTimeoutMs"));
    assertEquals("45000", shippedDefault("idpRetryWindowMs"));
    assertEquals("300000", shippedDefault("basicCacheTtlMs"));
    assertEquals("1024", shippedDefault("basicCacheSize"));
  }

  // --- the browser gate's own decisions ----------------------------------------------------------

  @Test
  void theBrowserGateShipsOffAndWithTheContractedNames() throws Exception {
    // OFF is the rollout plan: the gate lands before idp can issue a session and before the
    // gateway can read the headers, so it ships inert and is flipped as a step of its own.
    assertEquals("false", sessionDefault("enabled"));
    // The cookie and the path are a contract with qits-platform-idp and its SPA, so they are pinned
    // here rather than left to a deployment to keep in step.
    assertEquals("qits-session", sessionDefault("cookieName"));
    assertEquals("http://localhost:8080", sessionDefault("canonicalOrigin"));
    // The names a clone serves, which are read right to left now: every one of them is inside the
    // platform's own project, `qits`. Both of its shapes are listed, because which one it serves is
    // its `supportsEnvironments` flag and that is live data rather than configuration — `*.qits`
    // while it has no environments, `*.prod.qits` while it has. The canonical origin stays in the
    // list because startup refuses a list without it.
    assertEquals(
        "localhost:8080,qits.localhost:8080,*.qits.localhost:8080,*.prod.qits.localhost:8080",
        sessionDefault("browserHosts"));
    assertEquals("/idp/login", sessionDefault("loginPath"));
    assertEquals("/idp/", sessionDefault("anonymousPrefixes"));
    assertEquals("30000", sessionDefault("cacheTtlMs"));
    assertEquals("1024", sessionDefault("cacheSize"));
    assertEquals("60000", sessionDefault("staleGraceMs"));
  }

  @Test
  void theEdgesOwnIdpCredentialHasNoDefault() throws Exception {
    // A credential is a deployment fact. The bootstrap injects QITS_EDGE_SESSIONS_CLIENT_ID and
    // QITS_EDGE_SESSIONS_CLIENT_SECRET, and a value here would be a client id every installation
    // shared.
    assertNull(SessionsConfig.class.getMethod("clientId").getAnnotation(WithDefault.class));
    assertNull(SessionsConfig.class.getMethod("clientSecret").getAnnotation(WithDefault.class));
  }

  // --- the session client's resource-var fallback (service-client-identity-plan.md C4/D6/D7) -----

  @Test
  void theSessionClientReadsTheResourceVarsFirstThenTheOlderNames() throws Exception {
    // Read off the SHIPPED application.properties expression, not a copy of it, so a rewritten
    // expression fails this test rather than only production.
    assertEquals(Optional.empty(), sessionClientId(Map.of()), "neither set is still absent");
    assertEquals(
        Optional.of("dev-qits-edge"),
        sessionClientId(Map.of("QITS_EDGE_SESSIONS_CLIENT_ID", "dev-qits-edge")),
        "the older name still works, unchanged, until a resource is declared");
    assertEquals(
        Optional.of("qits-platform-edge"),
        sessionClientId(
            Map.of(
                "QITS_RESOURCE_IDP_CLIENT_ID", "qits-platform-edge",
                "QITS_EDGE_SESSIONS_CLIENT_ID", "dev-qits-edge")),
        "the resource var wins, so a cutover reads it without deleting the extras entry first");
  }

  @Test
  void theSessionSecretFollowsTheSameFallback() throws Exception {
    assertEquals(Optional.empty(), sessionClientSecret(Map.of()));
    assertEquals(
        Optional.of("old-secret"),
        sessionClientSecret(Map.of("QITS_EDGE_SESSIONS_CLIENT_SECRET", "old-secret")));
    assertEquals(
        Optional.of("new-secret"),
        sessionClientSecret(
            Map.of(
                "QITS_RESOURCE_IDP_CLIENT_SECRET", "new-secret",
                "QITS_EDGE_SESSIONS_CLIENT_SECRET", "old-secret")));
  }

  @Test
  void theIdpUrlReadsTheResourceVarFirstThenTheOlderNameThenTheShippedAddress() throws Exception {
    assertEquals("http://qits-platform-idp:8080/idp", idpUrl(Map.of()));
    assertEquals(
        "http://old-idp:8080/idp", idpUrl(Map.of("QITS_IDP_URL", "http://old-idp:8080/idp")));
    assertEquals(
        "http://new-idp:8080/idp",
        idpUrl(
            Map.of(
                "QITS_RESOURCE_IDP_URL", "http://new-idp:8080/idp",
                "QITS_IDP_URL", "http://old-idp:8080/idp")));
  }

  /**
   * THE SHIPPED DIAL ADDRESS RESOLVES, AND IT IS NOT THE ISSUER.
   *
   * <p>{@code qits.idp.dial-url}'s default is a nested expression — an env name whose fallback is
   * itself an expression — so a malformed one would not be a wrong value but a config expansion
   * that throws at startup, which on this service is the platform's only published listener failing
   * to boot and the deploy rolling back. {@code IdpTest} cannot catch that: it constructs {@link
   * Idp} with values it supplies. This reads the shipped file with expansion on, which is the only
   * place the default's own text is exercised.
   *
   * <p>The second assertion is the point of the split. Under the same empty environment the issuer
   * stays bare and the dial address carries the tier, so a change that collapsed the two keys back
   * into one would fail here as well as in {@code IdpTest}.
   */
  @Test
  void theShippedDialAddressDerivesTheTierAndDiffersFromTheIssuer() throws Exception {
    assertEquals("http://dev-qits-platform-idp:8080/idp", dialUrl(Map.of()));
    assertNotEquals(
        idpUrl(Map.of()),
        dialUrl(Map.of()),
        "the issuer is the iss claim and the dial-url is an address; shipping them equal is the"
            + " re-merge this split exists to prevent");

    assertEquals(
        "http://prod-qits-platform-idp:8080/idp",
        dialUrl(Map.of("QITS_ENVIRONMENT", "prod")),
        "the tier is derived from what qits-deployments injects, not written down");
    assertEquals(
        "http://stated:8080/idp",
        dialUrl(Map.of("QITS_IDP_DIAL_URL", "http://stated:8080/idp")),
        "and a deployment can still state it outright");
  }

  private static String dialUrl(Map<String, String> env) throws Exception {
    return applicationProperties(env)
        .build()
        .getOptionalValue("qits.idp.dial-url", String.class)
        .orElseThrow();
  }

  private static Optional<String> sessionClientId(Map<String, String> env) throws Exception {
    return applicationProperties(env)
        .withMapping(SessionsConfig.class)
        .build()
        .getConfigMapping(SessionsConfig.class)
        .clientId();
  }

  private static Optional<String> sessionClientSecret(Map<String, String> env) throws Exception {
    return applicationProperties(env)
        .withMapping(SessionsConfig.class)
        .build()
        .getConfigMapping(SessionsConfig.class)
        .clientSecret();
  }

  private static String idpUrl(Map<String, String> env) throws Exception {
    return applicationProperties(env)
        .build()
        .getOptionalValue("qits.idp.url", String.class)
        .orElseThrow();
  }

  /**
   * The shipped {@code application.properties}, with expression expansion on (off by default on a
   * bare builder) and one synthetic, higher-ordinal source standing in for the environment
   * variables a deployment would set.
   *
   * <p>The test JVM's classpath carries a SECOND {@code application.properties} — this repository's
   * own, under {@code src/test/resources} — so {@code getResource} alone cannot be trusted to pick
   * the shipped one: the two shadow each other in classpath order rather than merging. This picks
   * the copy that actually defines {@code qits.idp.url}, which only the shipped one does.
   */
  private static SmallRyeConfigBuilder applicationProperties(Map<String, String> env)
      throws Exception {
    var urls = EdgeChallengeTest.class.getClassLoader().getResources("application.properties");
    java.net.URL shipped = null;
    while (urls.hasMoreElements()) {
      java.net.URL candidate = urls.nextElement();
      if (new PropertiesConfigSource(candidate, 250).getValue("qits.idp.url") != null) {
        shipped = candidate;
        break;
      }
    }
    if (shipped == null) {
      throw new IllegalStateException(
          "no application.properties on the test classpath defines qits.idp.url");
    }
    return new SmallRyeConfigBuilder()
        .addDefaultInterceptors()
        .withSources(new PropertiesConfigSource(shipped, 250))
        .withSources(new PropertiesConfigSource(env, "env", 300));
  }

  @Test
  void theSessionCookieIsReadOutOfEverythingABrowserSends() {
    // A browser sends every cookie it holds for the host, in whatever order, with spaces after the
    // semicolons — and a server is allowed to quote a value.
    assertEquals("abc", EdgeSessions.cookieValue("qits-session=abc", "qits-session"));
    assertEquals(
        "abc", EdgeSessions.cookieValue("theme=dark; qits-session=abc; tz=CET", "qits-session"));
    assertEquals("abc", EdgeSessions.cookieValue("qits-session=\"abc\"", "qits-session"));
    assertNull(EdgeSessions.cookieValue("theme=dark", "qits-session"));
    assertNull(EdgeSessions.cookieValue("qits-session=", "qits-session"));
    assertNull(EdgeSessions.cookieValue(null, "qits-session"));
    // Cookie names are case-sensitive, so this is a different cookie and not this one.
    assertNull(EdgeSessions.cookieValue("QITS-SESSION=abc", "qits-session"));
  }

  @Test
  void browserReturnHostsAreAnExplicitAuthorityAllowList() {
    assertEquals(
        Set.of("wohlben.eu", "prod.wohlben.eu"),
        EdgeSessions.browserHosts(List.of("wohlben.eu", "PROD.wohlben.eu")));
    assertNull(EdgeSessions.authority("https://evil.example"));
    assertNull(EdgeSessions.authority("evil.example/path"));
    assertNull(EdgeSessions.authority("user@evil.example"));
    assertNull(EdgeSessions.authority("evil.example?not-an-authority"));
  }

  @Test
  void aWildcardEntryCoversExactlyOneLabel() {
    // The whole matcher, and the reason it is not a suffix test. The names are read right to left —
    // `<app>[.<env>].<project>.<domain>` — so a wildcard sits in front of a project's innermost
    // door and covers that project's applications without listing them: `*.dev.acme.wohlben.eu` for
    // an env-supporting project, `*.qits.wohlben.eu` for an env-less one.
    Set<String> exact =
        EdgeSessions.browserHosts(List.of("wohlben.eu", "dev.acme.wohlben.eu", "qits.wohlben.eu"));
    List<String> wildcards =
        EdgeSessions.wildcardBrowserHosts(
            List.of("wohlben.eu", "*.dev.acme.wohlben.eu", "*.qits.wohlben.eu"));

    assertTrue(EdgeSessions.browserHost("wohlben.eu", exact, wildcards), "an entry");
    assertTrue(EdgeSessions.browserHost("dev.acme.wohlben.eu", exact, wildcards), "and another");
    assertTrue(
        EdgeSessions.browserHost("ci.dev.acme.wohlben.eu", exact, wildcards),
        "an app of an env-supporting project");
    assertTrue(
        EdgeSessions.browserHost("editor.dev.acme.wohlben.eu", exact, wildcards),
        "the editor, an app vhost like any other");
    assertTrue(
        EdgeSessions.browserHost("projects.qits.wohlben.eu", exact, wildcards),
        "an app of an env-LESS project, which carries no environment label at all");
    // TWO labels is another site to a browser, and a return target this process must not reflect —
    // subdomain takeover is what an open list would cost.
    assertFalse(EdgeSessions.browserHost("evil.co.dev.acme.wohlben.eu", exact, wildcards));
    assertFalse(
        EdgeSessions.browserHost("ci.dev.qits.wohlben.eu", exact, wildcards),
        "an environment label this list did not open is two labels in front of `qits`");
    assertFalse(
        EdgeSessions.browserHost("ci.dev.gizmo.wohlben.eu", exact, wildcards),
        "a project nobody listed, which is the whole point of an allow-list");
    assertFalse(EdgeSessions.browserHost("a.b.c.dev.acme.wohlben.eu", exact, wildcards), "three");
    assertFalse(
        EdgeSessions.browserHost("dev.acme.wohlben.eu.evil.example", exact, wildcards),
        "the entry is a suffix of this name and matches nothing");
    // The port is part of the authority on both sides, so a name on another port matches nothing.
    assertFalse(EdgeSessions.browserHost("ci.dev.acme.wohlben.eu:8443", exact, wildcards));
    assertFalse(EdgeSessions.browserHost(null, exact, wildcards));
  }

  @Test
  void onlyARequestThatCouldRenderALoginPageIsRedirected() {
    // Sec-Fetch-Mode answers it whenever it is there: `navigate` is the one value that means a
    // document is being loaded, and everything else is a request made by a page that already
    // exists.
    assertTrue(EdgeSessions.isNavigation(HttpMethod.GET, "navigate", "text/html"));
    assertTrue(EdgeSessions.isNavigation(HttpMethod.POST, "navigate", null), "a form post is one");
    assertFalse(EdgeSessions.isNavigation(HttpMethod.GET, "cors", "text/html"));
    assertFalse(EdgeSessions.isNavigation(HttpMethod.GET, "websocket", "text/html"));
    assertFalse(EdgeSessions.isNavigation(HttpMethod.GET, "no-cors", null));
    // With no header at all — curl, an old client — the method and Accept are what a navigation
    // looked like before the header existed.
    assertTrue(
        EdgeSessions.isNavigation(
            HttpMethod.GET, null, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8"));
    assertFalse(EdgeSessions.isNavigation(HttpMethod.GET, null, "application/json"));
    assertFalse(EdgeSessions.isNavigation(HttpMethod.GET, null, "text/event-stream"));
    assertFalse(EdgeSessions.isNavigation(HttpMethod.POST, null, "text/html"));
    assertFalse(EdgeSessions.isNavigation(HttpMethod.GET, null, null));
  }

  @Test
  void theRedirectTargetCanOnlyEverBeAPathOnThisHost() {
    // An open redirect through the platform's own login page: the value is where a browser is sent
    // AFTER authenticating, so anything that can name another origin turns login into a
    // redirector.
    assertEquals("/projects/7?tab=runs", EdgeSessions.redirectTarget("/projects/7?tab=runs"));
    assertEquals("/", EdgeSessions.redirectTarget("//evil.example.com/steal"));
    assertEquals("/", EdgeSessions.redirectTarget("https://evil.example.com/steal"));
    // A backslash is a slash to every browser's URL parser, whatever the RFC says.
    assertEquals("/", EdgeSessions.redirectTarget("/\\evil.example.com"));
    assertEquals("/", EdgeSessions.redirectTarget("\\\\evil.example.com"));
    // A carriage return in a header value is a second header.
    assertEquals("/", EdgeSessions.redirectTarget("/ok\r\nLocation: http://evil.example.com"));
    assertEquals("/", EdgeSessions.redirectTarget(null));
    assertEquals("/", EdgeSessions.redirectTarget(""));
  }

  @Test
  void theAnonymousCarveOutIsAPrefixAndNothingElse() {
    List<String> idp = List.of("/idp/");
    assertTrue(EdgeSessions.anonymous("/idp/login", idp));
    assertTrue(EdgeSessions.anonymous("/idp/assets/main-ab12cd.js", idp));
    assertTrue(EdgeSessions.anonymous("/idp/api/auth/login", idp));
    assertFalse(EdgeSessions.anonymous("/idpsomething", idp), "the slash is part of the prefix");
    assertFalse(EdgeSessions.anonymous("/projects", idp));
    assertFalse(EdgeSessions.anonymous(null, idp));
    assertEquals(List.of("/idp/"), EdgeSessions.prefixes(List.of(" /idp/ ", "", "  ")));
  }

  @Test
  void aRoleSetBecomesOneHeaderValueThatCannotBeSplit() {
    // Comma-separated is safe because a role is $app:$resource:$role and holds no comma. One that
    // somehow did would arrive downstream as TWO roles, so it is dropped rather than carried.
    assertEquals("qits:admin", EdgeSessions.rolesHeader(new JsonArray(List.of("qits:admin"))));
    assertEquals("", EdgeSessions.rolesHeader(new JsonArray()));
    assertEquals("", EdgeSessions.rolesHeader(null));
    assertEquals(
        "qits:admin",
        EdgeSessions.rolesHeader(new JsonArray(List.of("qits:admin", "smuggled,qits:root"))));
    assertEquals(
        "qits:admin",
        EdgeSessions.rolesHeader(new JsonArray(List.of("qits:admin", "a\r\nX-Qits-User: root"))));
  }

  @Test
  void anIdentityWithNoUsableNameIsRefusedRatherThanSanitised() {
    // Both fields go into headers an upstream believes unconditionally. A strange answer must never
    // become a strange identity, and nothing about the platform's own idp sends one.
    assertNotNull(EdgeSessions.read("{\"userId\":\"an-id\",\"username\":\"operator\"}"));
    assertNull(EdgeSessions.read("{\"userId\":\"an-id\"}"));
    assertNull(EdgeSessions.read("{\"username\":\"operator\"}"));
    assertNull(EdgeSessions.read("{\"userId\":\"an-id\",\"username\":\"a\\r\\nb\"}"));
    assertNull(EdgeSessions.read("not json at all"));
  }

  @Test
  void aSessionWithNoReadableExpiryIsStillBelievedForTheCachesOwnWindow() {
    // idp has just said the session is good; a date this process could not parse is not a reason to
    // log somebody out, and the cache TTL bounds the belief either way.
    assertEquals(
        Long.MAX_VALUE,
        EdgeSessions.read("{\"userId\":\"an-id\",\"username\":\"operator\"}").expiresAtMillis());
    assertEquals(
        java.time.Instant.parse("2030-01-01T00:00:00Z").toEpochMilli(),
        EdgeSessions.read(
                "{\"userId\":\"an-id\",\"username\":\"operator\","
                    + "\"expiresAt\":\"2030-01-01T00:00:00Z\"}")
            .expiresAtMillis());
  }

  private static String shippedDefault(String key) throws Exception {
    return AuthConfig.class.getMethod(key).getAnnotation(WithDefault.class).value();
  }

  /** {@link AuthConfig} as SmallRye reads it from these properties and the shipped defaults. */
  private static AuthConfig authConfig(Map<String, String> properties) {
    return new SmallRyeConfigBuilder()
        .withMapping(AuthConfig.class)
        .withSources(new PropertiesConfigSource(properties, "test", 500))
        .build()
        .getConfigMapping(AuthConfig.class);
  }

  private static String sessionDefault(String key) throws Exception {
    return SessionsConfig.class.getMethod(key).getAnnotation(WithDefault.class).value();
  }

  private static String encode(String plain) {
    return java.util.Base64.getEncoder()
        .encodeToString(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static HostEnvironments.Route app(String app, String environment) {
    return new HostEnvironments.Route(environment, app, null);
  }
}
