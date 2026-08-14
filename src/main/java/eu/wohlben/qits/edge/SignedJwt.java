package eu.wohlben.qits.edge;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

/**
 * One compact JWS, split into the parts a validator needs, plus the checks it makes. Framework-free
 * on purpose: this is the second piece of edge behaviour worth pinning without booting an
 * application, and {@code SignedJwtTest} is where the malformed shapes live.
 *
 * <p><b>Why this rather than an extension.</b> The edge validates ONE issuer's RS256 tokens against
 * a JWKS it fetches itself, and that is a hundred lines of JDK crypto. {@code quarkus-smallrye-jwt}
 * would do it too, at the cost of a sixth extension on a process whose whole design point is that
 * it has five — every one of them native-image size, build time and reflection surface on the one
 * component that must be up before anything on the platform is reachable.
 *
 * @param kid the {@code kid} header — which published key signed this
 * @param algorithm the {@code alg} header; only {@code RS256} is accepted
 * @param claims the payload, verbatim
 * @param signed the bytes the signature covers: {@code <header>.<payload>}, ASCII, as they arrived
 * @param signature the decoded signature
 */
public record SignedJwt(
    String kid, String algorithm, JsonObject claims, byte[] signed, byte[] signature) {

  /** The one algorithm the idp signs with, and so the one this accepts. */
  public static final String RS256 = "RS256";

  /**
   * Split a compact serialization.
   *
   * @throws IllegalArgumentException on anything that is not three base64url parts of JSON. The
   *     caller turns that into a 401 — a malformed token is an unauthenticated caller, never a 500.
   */
  public static SignedJwt parse(String compact) {
    if (compact == null || compact.isBlank()) {
      throw new IllegalArgumentException("no token");
    }
    String[] parts = compact.split("\\.", -1);
    if (parts.length != 3) {
      throw new IllegalArgumentException(
          "a JWT has three dot-separated parts, not " + parts.length);
    }
    JsonObject header = json(parts[0], "header");
    JsonObject claims = json(parts[1], "payload");
    byte[] signature = decode(parts[2], "signature");
    return new SignedJwt(
        header.getString("kid"),
        header.getString("alg"),
        claims,
        (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII),
        signature);
  }

  /**
   * Whether this token was signed by that key. False rather than an exception for a bad signature;
   * a key that cannot verify at all is a configuration problem and throws.
   */
  public boolean signatureMatches(PublicKey key) {
    try {
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(key);
      verifier.update(signed);
      return verifier.verify(signature);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("SHA256withRSA is not usable in this JVM", e);
    }
  }

  /**
   * What is wrong with the claims, or null when nothing is. Signature is NOT checked here — that
   * needs a key, and the key is chosen by {@link #kid()}.
   *
   * @param issuer the {@code iss} every accepted token must carry, exactly
   * @param audience the audience the token must name; the registry permission, per the campaign's
   *     P-idp-3 — edge gates on the audience and shapes docker's own {@code scope} away
   * @param now the moment to judge {@code exp} against
   * @param clockSkewSeconds how far the two clocks may disagree
   */
  public String problem(String issuer, String audience, Instant now, long clockSkewSeconds) {
    if (!RS256.equals(algorithm)) {
      // Refusing `none` is the point, and refusing every other alg with it is the cheap way to do
      // it: an accepted algorithm the idp never signs with is an accepted algorithm we do not
      // check.
      return "the token is not " + RS256;
    }
    if (kid == null || kid.isBlank()) {
      return "the token names no signing key";
    }
    if (!issuer.equals(claims.getString("iss"))) {
      return "the token was not issued by " + issuer;
    }
    Long expiry = number(claims.getValue("exp"));
    if (expiry == null) {
      return "the token does not expire";
    }
    if (now.getEpochSecond() > expiry + clockSkewSeconds) {
      return "the token expired";
    }
    if (!audiences().contains(audience)) {
      return "the token is not for " + audience;
    }
    return null;
  }

  /** The {@code aud} claim, which JWT allows to be one string or an array of them. */
  public JsonArray audiences() {
    Object aud = claims.getValue("aud");
    if (aud instanceof JsonArray array) {
      return array;
    }
    return aud == null ? new JsonArray() : new JsonArray().add(aud);
  }

  private static Long number(Object value) {
    return value instanceof Number n ? n.longValue() : null;
  }

  private static JsonObject json(String part, String what) {
    try {
      return new JsonObject(new String(decode(part, what), StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("the " + what + " is not JSON");
    }
  }

  private static byte[] decode(String part, String what) {
    try {
      return Base64.getUrlDecoder().decode(part);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("the " + what + " is not base64url");
    }
  }
}
