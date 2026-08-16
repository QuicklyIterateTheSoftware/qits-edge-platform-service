package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonObject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The token check, without an application. Every case here is a way a request gets a 401, and the
 * ones worth having are the ones a validator would silently pass: another key, another issuer,
 * another audience, {@code alg: none}.
 */
class SignedJwtTest {

  private static final String ISSUER = "http://qits-platform-idp:8080/idp";
  private static final String AUDIENCE = "qits-platform-artifacts";

  @Test
  void aTokenTheIdpIssuedPassesEveryCheck() {
    SignedJwt jwt = SignedJwt.parse(TestTokens.valid(ISSUER, List.of(AUDIENCE)));
    assertEquals(TestTokens.KID, jwt.kid());
    assertEquals("RS256", jwt.algorithm());
    assertNull(jwt.problem(ISSUER, AUDIENCE, Instant.now(), 30));
    assertTrue(jwt.signatureMatches(TestTokens.IDP.getPublic()));
  }

  @Test
  void anotherKeysSignatureDoesNotVerify() {
    SignedJwt jwt =
        SignedJwt.parse(
            TestTokens.mint(
                TestTokens.IMPOSTOR,
                TestTokens.KID,
                "RS256",
                TestTokens.claims(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300))));
    // The claims are perfect — this is exactly the token a validator that only reads claims
    // accepts.
    assertNull(jwt.problem(ISSUER, AUDIENCE, Instant.now(), 30));
    assertFalse(jwt.signatureMatches(TestTokens.IDP.getPublic()));
  }

  @Test
  void anAlgorithmThatIsNotRs256IsRefusedBeforeAnythingElse() {
    // `none` is the classic, and refusing every alg but one is the cheap way to cover it: an
    // accepted algorithm the idp never signs with is an accepted algorithm nobody checks.
    SignedJwt jwt =
        SignedJwt.parse(
            TestTokens.mint(
                TestTokens.IDP,
                TestTokens.KID,
                "none",
                TestTokens.claims(ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300))));
    assertEquals("the token is not RS256", jwt.problem(ISSUER, AUDIENCE, Instant.now(), 30));
  }

  @Test
  void anotherIssuersTokenIsRefused() {
    SignedJwt jwt = SignedJwt.parse(TestTokens.valid("http://elsewhere/idp", List.of(AUDIENCE)));
    assertNotNull(jwt.problem(ISSUER, AUDIENCE, Instant.now(), 30));
  }

  @Test
  void anotherAudiencesTokenIsRefused() {
    SignedJwt jwt = SignedJwt.parse(TestTokens.valid(ISSUER, List.of("prod-qits-ci")));
    assertEquals(
        "the token is not for " + AUDIENCE, jwt.problem(ISSUER, AUDIENCE, Instant.now(), 30));
  }

  @Test
  void oneAudienceAmongSeveralIsEnough() {
    // idp names the client's WHOLE allowed list when the grant asked for no audience, which is what
    // the edge's token broker does — so the gate has to read `aud` as a set, not as a string.
    SignedJwt jwt =
        SignedJwt.parse(
            TestTokens.valid(ISSUER, List.of("prod-qits-ci", AUDIENCE, "prod-qits-deployments")));
    assertNull(jwt.problem(ISSUER, AUDIENCE, Instant.now(), 30));
  }

  @Test
  void aSingleStringAudienceIsRead() {
    // JWT allows `aud` to be one string rather than an array. idp always writes an array; a
    // validator that only handled the array would still be wrong.
    SignedJwt jwt =
        SignedJwt.parse(
            TestTokens.mint(
                TestTokens.IDP,
                TestTokens.KID,
                "RS256",
                new JsonObject()
                    .put("iss", ISSUER)
                    .put("aud", AUDIENCE)
                    .put("exp", Instant.now().plusSeconds(300).getEpochSecond())));
    assertNull(jwt.problem(ISSUER, AUDIENCE, Instant.now(), 30));
  }

  @Test
  void anExpiredTokenIsRefused_butNotWithinTheSkew() {
    Instant expiry = Instant.now().minusSeconds(10);
    SignedJwt jwt =
        SignedJwt.parse(
            TestTokens.mint(
                TestTokens.IDP,
                TestTokens.KID,
                "RS256",
                TestTokens.claims(ISSUER, List.of(AUDIENCE), expiry)));
    assertEquals("the token expired", jwt.problem(ISSUER, AUDIENCE, Instant.now(), 0));
    assertNull(jwt.problem(ISSUER, AUDIENCE, Instant.now(), 60));
  }

  @Test
  void aTokenWithNoExpiryIsRefused() {
    SignedJwt jwt =
        SignedJwt.parse(
            TestTokens.mint(
                TestTokens.IDP,
                TestTokens.KID,
                "RS256",
                new JsonObject().put("iss", ISSUER).put("aud", AUDIENCE)));
    assertEquals("the token does not expire", jwt.problem(ISSUER, AUDIENCE, Instant.now(), 30));
  }

  @Test
  void malformedInputThrowsRatherThanReturningSomethingHalfRead() {
    // The caller turns this into a 401. A half-parsed token that reached a signature check would be
    // the dangerous shape, so parse refuses outright.
    assertThrows(IllegalArgumentException.class, () -> SignedJwt.parse(null));
    assertThrows(IllegalArgumentException.class, () -> SignedJwt.parse(""));
    assertThrows(IllegalArgumentException.class, () -> SignedJwt.parse("not-a-token"));
    assertThrows(IllegalArgumentException.class, () -> SignedJwt.parse("a.b"));
    assertThrows(IllegalArgumentException.class, () -> SignedJwt.parse("a.b.c.d"));
    assertThrows(IllegalArgumentException.class, () -> SignedJwt.parse("!!!.!!!.!!!"));
  }

  @Test
  void aPublishedKeySetIsReadBackIntoTheKeyThatSigned() {
    // The JWKS parse and the signature check, joined: a modulus read with the wrong sign convention
    // produces a key that verifies nothing, and nothing else would catch it.
    SignedJwt jwt = SignedJwt.parse(TestTokens.valid(ISSUER, List.of(AUDIENCE)));
    assertTrue(jwt.signatureMatches(IdpKeys.parse(TestTokens.jwks()).get(TestTokens.KID)));
  }

  @Test
  void aJwksWithoutKeysIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> IdpKeys.parse(new JsonObject()));
  }
}
