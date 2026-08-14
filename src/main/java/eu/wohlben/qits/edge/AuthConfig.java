package eu.wohlben.qits.edge;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Optional;

/**
 * Where the edge terminates idp authentication, and how hard.
 *
 * <p><b>Two switches, deliberately not one.</b> The application vhosts are new — nothing reached
 * them before the ingress campaign — so they enforce from their first request and there is no
 * "before" to be compatible with. The environment vhost is the platform's whole existing traffic,
 * which authenticates one hop further in at the environment gateway; turning that on is a separate,
 * later step, taken when the gateway's own termination moves out here.
 *
 * <p><b>And one exemption, which is narrower than a third switch.</b> {@link #anonymousReadApps()}
 * names the app vhosts whose READS are open. It does not turn a vhost off — writes on the same name
 * still need a token — so it cannot become an accidental way to publish a whole service.
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
   * The app labels — the {@code $app} of {@code $app.$env.$domain} — whose vhosts serve {@code GET}
   * and {@code HEAD} to anyone. Every other method on those same names still needs a valid token,
   * and an app that is not named here is gated on every method as before.
   *
   * <p><b>Why the gate is method-shaped rather than name-shaped.</b> The reads are what a machine
   * with no credential has to be able to do: a {@code docker pull} of a base image on a fresh node,
   * a {@code git clone} of a public repository, a dependency fetch from the mirror — each of them a
   * bootstrap step that happens BEFORE there is anything to hold a token. The writes are the ones
   * that change what the platform will run, and none of them is a bootstrap step, so they keep the
   * whole check.
   *
   * <p><b>No default, which is an empty list.</b> A default here would open reads on a deployment
   * that never asked for it, and the app names it would open are exactly the ones worth closing. A
   * deployment names its own:
   *
   * <pre>
   * QITS_EDGE_AUTH_ANONYMOUS_READ_APPS=registry,mirror
   * </pre>
   *
   * <p>The list is read once at startup and matched against the app label the Host name resolved
   * to, so it reaches nothing but an app vhost — the environment vhost's behaviour is untouched by
   * any value here, and a label the edge does not route is still a 404 rather than an open door.
   */
  Optional<List<String>> anonymousReadApps();

  /**
   * The audience a token must name to pass, with {@code {env}} standing in for the environment the
   * vhost named — the same placeholder, and the same semantics, as the host patterns in {@link
   * EdgeConfig}.
   *
   * <p>The campaign's P-idp-3: the platform's claim model has no registry scope and needs none —
   * the existing audience is the permission, and docker's own {@code scope} parameter is shaped
   * away here rather than being given a meaning this process would have to enforce.
   *
   * <p><b>The placeholder is a boundary, not a convenience.</b> idp's audience values are
   * env-prefixed, so a pattern makes the token for one environment's registry fail at another
   * environment's vhost — one entry, and the tiers cannot unlock each other. A value with no
   * placeholder is a literal and still works, which is what a single-audience deployment wants.
   */
  @WithDefault("{env}-qits-artifacts")
  String audiencePattern();

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
