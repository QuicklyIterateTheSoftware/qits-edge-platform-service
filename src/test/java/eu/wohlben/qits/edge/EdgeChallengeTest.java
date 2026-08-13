package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The {@code WWW-Authenticate} value, character by character. It is a wire contract with a client
 * nobody here controls: docker parses it to find the token endpoint, and a challenge it cannot
 * parse fails a pull with no message in any log on either side.
 */
class EdgeChallengeTest {

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
