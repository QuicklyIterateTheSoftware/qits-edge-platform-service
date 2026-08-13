package eu.wohlben.qits.edge;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Where the edge terminates idp authentication, and how hard.
 *
 * <p><b>Two switches, deliberately not one.</b> The application vhosts are new — nothing reached
 * them before the ingress campaign — so they enforce from their first request and there is no
 * "before" to be compatible with. The environment vhost is the platform's whole existing traffic,
 * which authenticates one hop further in at the environment gateway; turning that on is a separate,
 * later step, taken when the gateway's own termination moves out here.
 */
@ConfigMapping(prefix = "qits.edge.auth")
public interface AuthConfig {

  /**
   * Whether an {@code $app.$env.$domain} request must carry a valid idp token. ON: these names are
   * the reason the edge authenticates at all, and every one of them fronts a service that has no
   * external auth of its own.
   */
  @WithDefault("true")
  boolean enforceOnApps();

  /**
   * Whether an {@code $env.$domain} request must carry one. OFF, and flipping it is a step of its
   * own: today every browser, SPA and API client on the platform reaches the environment gateway
   * through this path and authenticates THERE, so turning this on before that termination has moved
   * would answer every one of them with a 401 they cannot act on.
   */
  @WithDefault("false")
  boolean enforceOnEnvironments();

  /**
   * The audience a token must name to pass. The campaign's P-idp-3: the platform's claim model has
   * no registry scope and needs none — the existing audience is the permission, and docker's own
   * {@code scope} parameter is shaped away here rather than being given meaning.
   */
  @WithDefault("qits-platform-artifacts")
  String audience();

  /** How far this process' clock and idp's may disagree about {@code exp}, in seconds. */
  @WithDefault("30")
  long clockSkewSeconds();

  /**
   * The shortest gap between two JWKS fetches, in milliseconds. An unknown {@code kid} triggers one
   * refresh; this is what stops a caller with a made-up kid from turning that into a request per
   * request at the identity provider.
   */
  @WithDefault("5000")
  long jwksRefreshCooldownMs();
}
