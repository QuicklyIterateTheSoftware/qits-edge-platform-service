package eu.wohlben.qits.edge;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * qits-platform-idp's public signing keys, cached here and refreshed when a token names one this
 * process has not seen.
 *
 * <p><b>Offline validation is the design</b>, and the campaign's P-idp-2 is why: idp is
 * overlay-only, so a {@code docker login} client on the host cannot reach it — and the edge should
 * not put it on the path of every image pull either. Fetching the key set once and verifying
 * signatures locally keeps idp off that path entirely; the only per-request cost is arithmetic.
 *
 * <p><b>Unknown kid buys ONE fetch, behind a cooldown.</b> A key rotation is an operator retiring a
 * key or an idp landing on an empty database, and until the new set is picked up every token fails.
 * Without a cooldown, that same state lets any caller with a made-up {@code kid} drive one HTTP
 * request per request at the identity provider.
 */
@ApplicationScoped
public class IdpKeys {

  private static final Logger LOG = Logger.getLogger(IdpKeys.class);

  @Inject Vertx vertx;

  @Inject AuthConfig config;

  @Inject Idp idp;

  private HttpClient client;

  /** kid to key. Replaced whole on every successful fetch, never mutated. */
  private volatile Map<String, RSAPublicKey> keys = Map.of();

  private volatile long lastFetchMillis;

  /** The fetch in progress, so a burst of unknown-kid requests makes one request, not a burst. */
  private Future<Map<String, RSAPublicKey>> inFlight;

  @PostConstruct
  void open() {
    // Its own client, not the proxy's: that one is tuned for 64 concurrent layer pushes with no
    // idle timeout, which is the opposite of a small JSON GET that must fail fast.
    client = vertx.createHttpClient();
  }

  /**
   * The key that signed a token, or a failed future when there is none.
   *
   * @param kid the token's {@code kid} header
   */
  public Future<RSAPublicKey> find(String kid) {
    RSAPublicKey known = keys.get(kid);
    if (known != null) {
      return Future.succeededFuture(known);
    }
    return refresh()
        .compose(
            fetched -> {
              RSAPublicKey key = fetched.get(kid);
              return key != null
                  ? Future.succeededFuture(key)
                  : Future.failedFuture("no published key with kid " + kid);
            });
  }

  /** The kids currently held — the readiness payload's way of showing the cache is warm. */
  public int size() {
    return keys.size();
  }

  private synchronized Future<Map<String, RSAPublicKey>> refresh() {
    if (inFlight != null) {
      return inFlight;
    }
    if (System.currentTimeMillis() - lastFetchMillis < config.jwksRefreshCooldownMs()) {
      return Future.succeededFuture(keys);
    }
    Future<Map<String, RSAPublicKey>> fetch = fetch();
    inFlight = fetch;
    fetch.onComplete(
        result -> {
          synchronized (this) {
            inFlight = null;
            lastFetchMillis = System.currentTimeMillis();
          }
          if (result.succeeded()) {
            keys = result.result();
            LOG.infof("read %d signing keys from %s", keys.size(), idp.jwksUri());
          } else {
            LOG.errorf(result.cause(), "could not read the signing keys from %s", idp.jwksUri());
          }
        });
    return fetch;
  }

  private Future<Map<String, RSAPublicKey>> fetch() {
    RequestOptions options =
        new RequestOptions()
            .setMethod(HttpMethod.GET)
            .setAbsoluteURI(idp.jwksUri())
            // BOUNDED, and this one is not a nicety either: a fetch is SHARED — every request that
            // met an unknown kid waits on the same future, and `inFlight` is only cleared when it
            // completes. An idp that accepts the connection and never answers would therefore wedge
            // this cache for the life of the process, not for one request.
            .setConnectTimeout(config.idpCallTimeoutMs())
            .setTimeout(config.idpCallTimeoutMs());
    return client
        .request(options)
        .compose(HttpClientRequest::send)
        .compose(
            response ->
                response.statusCode() == 200
                    ? response.body()
                    : Future.failedFuture(idp.jwksUri() + " answered " + response.statusCode()))
        .map(body -> parse(new JsonObject(body)));
  }

  /**
   * A JWKS document to usable keys. RSA keys with a {@code kid} only — anything else in the
   * document is a key this process could not verify with anyway, and dropping it quietly is right:
   * a JWKS is allowed to carry keys for consumers other than us.
   */
  static Map<String, RSAPublicKey> parse(JsonObject document) {
    Map<String, RSAPublicKey> parsed = new LinkedHashMap<>();
    JsonArray keys = document.getJsonArray("keys");
    if (keys == null) {
      throw new IllegalArgumentException("a JWKS document has a `keys` array");
    }
    for (int i = 0; i < keys.size(); i++) {
      JsonObject jwk = keys.getJsonObject(i);
      String kid = jwk.getString("kid");
      if (kid == null || !"RSA".equals(jwk.getString("kty"))) {
        continue;
      }
      parsed.put(kid, rsa(jwk.getString("n"), jwk.getString("e")));
    }
    return Map.copyOf(parsed);
  }

  private static RSAPublicKey rsa(String modulus, String exponent) {
    try {
      // Sign 1: these are UNSIGNED big-endian values. new BigInteger(byte[]) would read a modulus
      // whose top bit is set as negative, and every signature would then fail to verify.
      RSAPublicKeySpec spec =
          new RSAPublicKeySpec(
              new BigInteger(1, Base64.getUrlDecoder().decode(modulus)),
              new BigInteger(1, Base64.getUrlDecoder().decode(exponent)));
      return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
    } catch (GeneralSecurityException | RuntimeException e) {
      throw new IllegalArgumentException("a published key is not a usable RSA key", e);
    }
  }
}
