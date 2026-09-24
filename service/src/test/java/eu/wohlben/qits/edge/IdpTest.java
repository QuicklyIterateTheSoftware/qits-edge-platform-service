package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * THE ISSUER AND THE ADDRESS ARE TWO FACTS, and these are the assertions that keep them apart.
 *
 * <p>Deleting the platform plane moved the idp from its bare alias to {@code <env>-<application>}.
 * The edge had one key for both jobs, so moving the address would have moved the {@code iss} claim
 * with it — and that claim is compared for equality by every consumer on the estate, so changing it
 * rejects every token in flight at once. The split is what lets the dial address follow the
 * deployment while the claim stays exactly where it is.
 *
 * <p>These tests are written to FAIL if anybody re-merges the two keys: every one of them sets the
 * two to different hosts and asserts which of them each answer is built from. On a platform that
 * has not cut over the two hold the same string, so a collapsed implementation would pass every
 * integration suite in this repository and be discovered on the estate.
 */
class IdpTest {

  private static Idp idp(String issuer, String dial) {
    Idp idp = new Idp();
    idp.configured = issuer;
    idp.configuredDial = Optional.ofNullable(dial);
    return idp;
  }

  @Test
  void theIssuerIsTheCLAIMAndNeverFollowsTheDialAddress() {
    Idp idp = idp("http://qits-platform-idp:8080/idp", "http://dev-qits-platform-idp:8080/idp");

    assertEquals(
        "http://qits-platform-idp:8080/idp",
        idp.issuer(),
        "the iss claim is a string that is compared, not a host that is dialled — it does not move"
            + " because the deployment did");
  }

  @Test
  void everyEndpointIsBuiltFromTheDialAddress() {
    Idp idp = idp("http://qits-platform-idp:8080/idp", "http://dev-qits-platform-idp:8080/idp");

    assertEquals("http://dev-qits-platform-idp:8080/idp/jwks", idp.jwksUri());
    assertEquals("http://dev-qits-platform-idp:8080/idp/token", idp.tokenEndpoint());
    assertEquals(
        "http://dev-qits-platform-idp:8080/idp/api/sessions/introspect",
        idp.introspectionEndpoint());
  }

  @Test
  void anUnsetDialAddressFallsBackToTheIssuerSoTheSplitIsAdditive() {
    Idp idp = idp("http://qits-platform-idp:8080/idp", null);

    assertEquals("http://qits-platform-idp:8080/idp/jwks", idp.jwksUri());
    assertEquals(
        "http://qits-platform-idp:8080/idp/token",
        idp.tokenEndpoint(),
        "an estate that has stated no dial address behaves exactly as it did before there were two"
            + " keys");
  }

  @Test
  void anEmptyDialAddressIsTreatedAsUnsetRatherThanAsAHostCalledNothing() {
    assertEquals(
        "http://qits-platform-idp:8080/idp/jwks",
        idp("http://qits-platform-idp:8080/idp", "   ").jwksUri(),
        "a blanked-out deployment value must not compose `/jwks` onto the empty string");
  }

  @Test
  void bothSidesAreTrimmedOfTrailingSlashesBeforeAnythingIsComposedOnto() {
    Idp idp = idp("http://qits-platform-idp:8080/idp//", "http://dev-qits-platform-idp:8080/idp/");

    assertEquals("http://qits-platform-idp:8080/idp", idp.issuer());
    assertEquals(
        "http://dev-qits-platform-idp:8080/idp/jwks",
        idp.jwksUri(),
        "a configured trailing slash is exactly the one character an issuer comparison fails on,"
            + " and a doubled one is exactly the 404 a key fetch fails on");
  }
}
