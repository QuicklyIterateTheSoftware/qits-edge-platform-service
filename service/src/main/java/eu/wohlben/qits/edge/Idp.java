package eu.wohlben.qits.edge;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Where qits-platform-idp is, in one key.
 *
 * <p>The same rule as {@code qits.observability.url} in this repository's properties file: ONE key
 * names the receiver, and the paths under it belong to that service rather than to a deployment, so
 * they are derived here instead of configured. qits-platform-idp derives its own {@code
 * <issuer>/token} and {@code <issuer>/jwks} from the same string in its {@code Issuer} class, so
 * the two sides cannot drift apart.
 *
 * <p>The value is also the {@code iss} every accepted token must carry, exactly — a consumer that
 * validated a token whose issuer differed by one character would be validating somebody else's.
 */
@ApplicationScoped
public class Idp {

  @ConfigProperty(name = "qits.idp.url")
  String configured;

  /** The issuer string: {@code qits.idp.url} trimmed, with any trailing slash removed. */
  public String issuer() {
    String url = configured.trim();
    while (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    return url;
  }

  /** {@code <issuer>/jwks} — the published signing keys. */
  public String jwksUri() {
    return issuer() + "/jwks";
  }

  /** {@code <issuer>/token} — RFC 6749 {@code client_credentials}, form encoded. */
  public String tokenEndpoint() {
    return issuer() + "/token";
  }

  /**
   * {@code <issuer>/api/sessions/introspect} — a browser session's cookie in, the user it belongs
   * to out. Derived like the two above, from the one key that names the receiver.
   *
   * <p>Under {@code /api} rather than beside {@code /token}: the protocol endpoints are the OIDC
   * ones and this is not an OIDC endpoint. It is idp's own API, guarded by the caller's HTTP Basic
   * client credentials, and it lives where the rest of idp's API does.
   */
  public String introspectionEndpoint() {
    return issuer() + "/api/sessions/introspect";
  }
}
