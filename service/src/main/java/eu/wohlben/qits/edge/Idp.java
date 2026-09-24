package eu.wohlben.qits.edge;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Where qits-platform-idp is, and what it calls itself — TWO keys, because they are two facts.
 *
 * <p>{@code qits.idp.url} is the {@code iss} every accepted token must carry, exactly: a consumer
 * that validated a token whose issuer differed by one character would be validating somebody
 * else's. It is a string that is COMPARED, and it does not have to resolve.
 *
 * <p>{@code qits.idp.dial-url} is the address this process actually connects to for keys, tokens
 * and introspection. It defaults to the issuer, which is what it was until the platform plane was
 * deleted — one key naming the receiver, every path under it derived here rather than configured,
 * because the paths belong to idp rather than to a deployment. The paths are still derived; what
 * moved is which of the two strings they hang off.
 *
 * <p><b>WHY THEY HAD TO COME APART, and it is not tidiness.</b> Deleting the plane moves every
 * platform service from a bare alias to {@code <env>-<application>}, and the old bare-named swarm
 * services are removed once nothing dials them. Dialling is what this class does three times. The
 * issuer is the one thing that CANNOT move with them: it is stamped into every token in flight and
 * compared for equality by every consumer, so changing it rejects every outstanding token at once,
 * estate-wide. Dual DNS buys a string comparison nothing — so the address moves and the claim
 * stays, which is only expressible once the two stop sharing a key.
 *
 * <p>qits-bootstrap-cli made the same split on its side first, {@code ${IDP}} against {@code
 * ${IDP_DIAL}}, and its AGENTS.md records the rule this class now also holds: DO NOT RE-MERGE THEM.
 * They read identically on a platform that has not cut over, which is exactly how they would get
 * collapsed back into one by somebody tidying up.
 */
@ApplicationScoped
public class Idp {

  @ConfigProperty(name = "qits.idp.url")
  String configured;

  @ConfigProperty(name = "qits.idp.dial-url")
  Optional<String> configuredDial;

  /** The issuer string: {@code qits.idp.url} trimmed, with any trailing slash removed. */
  public String issuer() {
    return trimmed(configured);
  }

  /**
   * The base this process CONNECTS to: {@code qits.idp.dial-url} when set, the issuer otherwise.
   *
   * <p>Falling back to the issuer is what makes the split additive — an estate that has not stated
   * a dial address behaves exactly as it did before there were two keys.
   */
  public String dialBase() {
    return configuredDial.map(this::trimmed).filter(url -> !url.isEmpty()).orElseGet(this::issuer);
  }

  /** {@code <dial>/jwks} — the published signing keys. */
  public String jwksUri() {
    return dialBase() + "/jwks";
  }

  /** {@code <dial>/token} — RFC 6749 {@code client_credentials}, form encoded. */
  public String tokenEndpoint() {
    return dialBase() + "/token";
  }

  /**
   * {@code <dial>/api/sessions/introspect} — a browser session's cookie in, the user it belongs to
   * out. Derived like the two above, from the address rather than from the claim.
   *
   * <p>Under {@code /api} rather than beside {@code /token}: the protocol endpoints are the OIDC
   * ones and this is not an OIDC endpoint. It is idp's own API, guarded by the caller's HTTP Basic
   * client credentials, and it lives where the rest of idp's API does.
   */
  public String introspectionEndpoint() {
    return dialBase() + "/api/sessions/introspect";
  }

  private String trimmed(String value) {
    String url = value.trim();
    while (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    return url;
  }
}
