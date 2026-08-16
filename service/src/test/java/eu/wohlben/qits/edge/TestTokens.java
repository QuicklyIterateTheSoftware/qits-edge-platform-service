package eu.wohlben.qits.edge;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * One RSA key pair, the JWKS that publishes it, and a minter for the tokens it signs — the whole of
 * what a test needs to stand in for qits-platform-idp.
 *
 * <p>Real RS256 rather than a stubbed validator, because the thing worth proving is that the edge
 * REFUSES: a token signed by another key, one that expired, one for another audience. A fake
 * validator would pass all three.
 */
final class TestTokens {

  static final String KID = "test-key";

  /** The key idp publishes and signs with. */
  static final KeyPair IDP = generate();

  /** A key idp does NOT publish — the "someone else signed this" case. */
  static final KeyPair IMPOSTOR = generate();

  private TestTokens() {}

  private static KeyPair generate() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException("no RSA in this JVM", e);
    }
  }

  /** The JWKS document the stub idp serves, publishing {@link #IDP} under {@link #KID}. */
  static JsonObject jwks() {
    RSAPublicKey key = (RSAPublicKey) IDP.getPublic();
    return new JsonObject()
        .put(
            "keys",
            new JsonArray()
                .add(
                    new JsonObject()
                        .put("kty", "RSA")
                        .put("use", "sig")
                        .put("alg", "RS256")
                        .put("kid", KID)
                        .put("n", unsigned(key.getModulus().toByteArray()))
                        .put("e", unsigned(key.getPublicExponent().toByteArray()))));
  }

  /** A token idp would issue: signed by the published key, live, for the given audiences. */
  static String valid(String issuer, List<String> audiences) {
    return mint(IDP, KID, "RS256", claims(issuer, audiences, Instant.now().plusSeconds(300)));
  }

  static JsonObject claims(String issuer, List<String> audiences, Instant expiry) {
    return new JsonObject()
        .put("iss", issuer)
        .put("sub", "a-client")
        .put("aud", new JsonArray(audiences))
        .put("iat", Instant.now().getEpochSecond())
        .put("exp", expiry.getEpochSecond());
  }

  /** Sign anything at all, so a test can build the token it wants refused. */
  static String mint(KeyPair keys, String kid, String algorithm, JsonObject claims) {
    String header =
        base64(
            new JsonObject()
                .put("alg", algorithm)
                .put("kid", kid)
                .encode()
                .getBytes(StandardCharsets.UTF_8));
    String payload = base64(claims.encode().getBytes(StandardCharsets.UTF_8));
    byte[] signature;
    try {
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign((RSAPrivateKey) keys.getPrivate());
      signer.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
      signature = signer.sign();
    } catch (Exception e) {
      throw new IllegalStateException("could not sign", e);
    }
    return header + "." + payload + "." + base64(signature);
  }

  private static String base64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** BigInteger.toByteArray prefixes a zero byte when the top bit is set; a JWK carries none. */
  private static String unsigned(byte[] bytes) {
    int start = 0;
    while (start < bytes.length - 1 && bytes[start] == 0) {
      start++;
    }
    byte[] trimmed = new byte[bytes.length - start];
    System.arraycopy(bytes, start, trimmed, 0, trimmed.length);
    return base64(trimmed);
  }
}
