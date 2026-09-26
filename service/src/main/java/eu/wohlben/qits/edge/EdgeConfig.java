package eu.wohlben.qits.edge;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The edge's whole configuration surface: which environments exist, which one is the fallback, and
 * and the direct application vhosts it owns.
 *
 * <p>There is no route table and no path knowledge here, deliberately. The edge demultiplexes by
 * <b>host name</b> only — an environment name, and since the ingress campaign an optional
 * application name in front of it. Deployment events own environment-vhost paths; {@link #apps()}
 * maps a whole NAME to a whole service, never a path to one.
 *
 * <p>Every upstream is derived from configuration ONLY. No part of a request selects a host or a
 * port: the Host name picks an environment out of a fixed list, and a name that is not in the list
 * picks the default. That is the SSRF guard, and it is why {@link #environments()} is a list rather
 * than a pattern the request could satisfy.
 *
 * <p>Since config sources include environment variables, a deployment declares all of it without a
 * file:
 *
 * <pre>
 * QITS_DOMAIN=wohlben.eu
 * QITS_EDGE_ENVIRONMENTS=prod,dev
 * QITS_EDGE_DEFAULT_ENVIRONMENT=prod
 * </pre>
 */
@ConfigMapping(prefix = "qits.edge")
public interface EdgeConfig {

  /**
   * <b>The one domain this platform states about itself</b>, and the primitive every composed name
   * here is built from: {@code wohlben.eu}, {@code localhost}.
   *
   * <p>It is a DEPLOYMENT fact of the estate rather than of this service, so it is injected under
   * the platform's own spelling — {@code QITS_DOMAIN}, beside {@code QITS_ENVIRONMENT}, written by
   * qits-deployments into every container — and {@code application.properties} maps that name onto
   * this key. One fact, stated once: the certificate's domain, the grammar every Host is read
   * against, the browser return authorities and the canonical origin are all THIS value, and none
   * of them is configured beside it. {@code QITS_EDGE_ACME_DOMAIN} and {@code
   * QITS_EDGE_SESSIONS_CANONICAL_ORIGIN} were the same fact under two more names and are gone.
   *
   * <p>It cannot be derived from a host name — {@code example.co.uk} is two labels of domain and
   * {@code localhost} is one — which is why it is stated at all.
   *
   * <p>The default is the local one, so a clone with no environment at all serves {@code
   * *.localhost:8080} exactly as it did.
   */
  @WithDefault("localhost")
  String domain();

  /**
   * The environments this edge can reach, by name. A Host name resolves to one of these or to
   * {@link #defaultEnvironment()}; nothing else is routable.
   *
   * <p>A name becomes a DNS label in an upstream host, so it is checked at startup against the
   * label charset — a name that could not be resolved is a configuration error worth failing on
   * rather than a 502 per request.
   */
  @WithDefault("prod")
  List<String> environments();

  /**
   * Where the apex domain and every unmatched Host name go. It must be one of {@link
   * #environments()}; a default naming an environment the edge cannot reach fails startup, because
   * the alternative is an edge that answers most of its traffic with a connection error.
   */
  @WithDefault("prod")
  String defaultEnvironment();

  /**
   * The applications an {@code $app.$env.$domain} host name may reach directly, keyed by the {@code
   * $app} label. A label with no entry is refused.
   *
   * <p>One entry ships in {@code application.properties}: {@code mirror}, whose host pattern is
   * {@code {env}-qits-platform-mirror}. A map entry cannot be unset by a later config source, only
   * overridden, so shipping one costs the ability to revoke it — which is free here and nowhere
   * else, because the pull-through cache is the platform's own and its address was never a decision
   * a deployment made. It carries the {@code {env}} placeholder like every other entry: the mirror
   * was a platform service addressed bare until that plane was deleted, and it is an ordinary
   * application in the one tier now.
   *
   * <p>An ENVIRONMENT's application is not shipped, and that stays the pre-ingress edge exactly:
   * its app label routes nowhere of its own until a deployment names it, one prefix per
   * application:
   *
   * <pre>
   * QITS_EDGE_APPS_REGISTRY_HOST_PATTERN={env}-qits-artifacts
   * QITS_EDGE_APPS_GITHOST_HOST_PATTERN={env}-qits-githost
   * </pre>
   */
  Map<String, App> apps();

  /** The startup proof that turns a persisted deployment snapshot into an authoritative one. */
  Projection projection();

  interface Projection {

    Catchup catchup();

    interface Catchup {

      /** False only in deliberately offline test/dev setups. */
      @WithDefault("true")
      boolean required();

      /** Delay between unsuccessful reads of qits-events while readiness remains down. */
      @WithDefault("PT1S")
      Duration retry();
    }
  }

  /**
   * One application's upstream, in the same shape as the environment gateway's above: a host
   * pattern plus a port, with per-environment overrides for the topologies a pattern cannot
   * describe.
   */
  interface App {

    /**
     * The audience accepted for this application's direct vhost. It defaults to the literal {@code
     * qits-platform} — the same string {@link AuthConfig#platformAudience()} ships — so a freshly
     * configured entry opens with roles alone, the open calling model's own rule; an application
     * such as githost or the editor can still name its own resource audience (today's {@code
     * QITS_EDGE_APPS_GITHOST_AUDIENCE_PATTERN}, {@code QITS_EDGE_APPS_EDITOR_AUDIENCE_PATTERN}),
     * and that explicitly configured value keeps working unchanged.
     */
    @WithDefault("qits-platform")
    String audiencePattern();

    /**
     * The upstream host, with {@code {env}} standing in for the environment the Host name named.
     *
     * <p>The placeholder is what keeps an environment's own services separate: {@code
     * registry.dev.localhost} resolves {@code {env}-qits-artifacts} to {@code dev-qits-artifacts},
     * and {@code registry.prod.localhost} to {@code prod-qits-artifacts}, from one entry.
     *
     * <p>A PLATFORM service names no placeholder — {@code qits-platform-mirror} is one process for
     * every environment — and that is the whole difference between the two kinds here.
     *
     * <p>No default: an application with no address is a configuration error worth failing the
     * startup on, not a 502 per request.
     */
    String hostPattern();

    /** The port the application listens on. Overridable per environment by {@link #hosts()}. */
    @WithDefault("8080")
    int port();

    /**
     * Per-environment overrides, {@code qits.edge.apps.<app>.hosts.<env> = host} or {@code
     * host:port}. It exists for a developer's local process and for this repository's test suite; a
     * stale override sends a whole tier's traffic to the wrong process.
     */
    Map<String, String> hosts();
  }
}
