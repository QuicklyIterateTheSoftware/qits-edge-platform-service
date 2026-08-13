package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The strings {@code EdgeAuth} builds, without booting an application: the {@code WWW-Authenticate}
 * value character by character, and the audience it demands.
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
}
