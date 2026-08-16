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
 * QITS_EDGE_ENVIRONMENTS=prod,dev
 * QITS_EDGE_DEFAULT_ENVIRONMENT=prod
 * </pre>
 */
@ConfigMapping(prefix = "qits.edge")
public interface EdgeConfig {

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
   * <p>Empty by default, which is the pre-ingress edge exactly: no app label routes anywhere of its
   * own, and only {@code $env.$domain} works.
   *
   * <p>A deployment names the map without a file, one prefix per application:
   *
   * <pre>
   * QITS_EDGE_APPS_REGISTRY_HOST_PATTERN={env}-qits-artifacts
   * QITS_EDGE_APPS_MIRROR_HOST_PATTERN=qits-platform-mirror
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
     * The audience accepted for this application's direct vhost. It defaults to the historic
     * registry audience so existing application entries retain their behaviour; applications such
     * as githost can name their own resource audience.
     */
    @WithDefault("{env}-qits-artifacts")
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
