package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.WithDefault;
import io.vertx.core.http.HttpMethod;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The decisions {@code EdgeAuth} makes without touching a socket: the {@code WWW-Authenticate}
 * value character by character, the audience it demands, and which requests skip the gate entirely.
 *
 * <p>The challenge is a wire contract with a client nobody here controls — docker parses it to find
 * the token endpoint, and one it cannot parse fails a pull with no message in any log on either
 * side.
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
  void theChallengePointsBackAtThisSameVhostsTokenEndpoint() {
    assertEquals(
        "Bearer realm=\"http://registry.dev.localhost:8080/token\",service=\"registry.dev.localhost:8080\"",
        EdgeAuth.bearerChallenge("http", "registry.dev.localhost:8080", "no bearer token"));
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
            HostEnvironments.Route.gateway("dev"), HttpMethod.GET, Set.of("mirror", "dev")));
  }

  @Test
  void anEmptyListGatesEverything() {
    // Today's behaviour, and the shipped default: with nothing named, every method on every app
    // vhost needs a token.
    assertFalse(EdgeAuth.anonymousRead(app("mirror", "dev"), HttpMethod.GET, Set.of()));
    assertFalse(EdgeAuth.anonymousRead(app("registry", "dev"), HttpMethod.HEAD, Set.of()));
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

  private static HostEnvironments.Route app(String app, String environment) {
    return new HostEnvironments.Route(environment, app, null);
  }
}
