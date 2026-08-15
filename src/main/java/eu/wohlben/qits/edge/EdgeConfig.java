package eu.wohlben.qits.edge;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The edge's whole configuration surface: which environments exist, which one is the fallback, and
 * how an environment name becomes an upstream address.
 *
 * <p>There is no route table and no path knowledge here, deliberately. The edge demultiplexes by
 * <b>host name</b> only — an environment name, and since the ingress campaign an optional
 * application name in front of it. Paths and segments stay the environment gateway's business one
 * hop further in: {@link #apps()} maps a whole NAME to a whole service, never a path to one.
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
   * The upstream host for an environment, with {@code {env}} standing in for its name. The platform
   * convention is one gateway per environment, named after it.
   */
  @WithDefault("{env}-qits-gateway")
  String upstreamHostPattern();

  /**
   * The port every environment gateway listens on. Overridable per environment by a {@code
   * host:port} value in {@link #upstreamHosts()}.
   */
  @WithDefault("8080")
  int upstreamPort();

  /**
   * How long the proxy waits for a TCP connection to an environment gateway, in milliseconds.
   *
   * <p>Vert.x defaults to 60 000, which was harmless while a gateway name either resolved to a live
   * container or refused the connection at once. Under swarm it is not: a service name resolves to
   * a virtual IP that exists before any task is healthy, so a connection to a gateway that is still
   * starting is not refused — it is dropped, and the request hangs for the whole timeout before the
   * edge answers 502. Five seconds keeps the outermost hop's failure fast, which is what a browser
   * (and whatever fronts this) can act on.
   *
   * <p>This is the CONNECT phase only. It has nothing to do with the idle timeout, which is 0 for
   * terminal sockets, SSE channels and slow layer pushes — see {@code EdgeRouter}.
   */
  @WithDefault("5000")
  int connectTimeoutMs();

  /**
   * Per-environment upstream overrides, {@code qits.edge.upstream-hosts.<env> = host} or {@code
   * host:port} — the same {@code host[:port]} shape qits-gateway's {@code proxy-hosts} takes.
   *
   * <p>It exists for the two topologies the pattern cannot describe: a developer running one
   * gateway on {@code localhost:8000}, and this repository's own test suite, where the gateways are
   * stub servers on ephemeral ports. A deployment on {@code qits-net} needs none of it — the
   * pattern is already the container's DNS name.
   *
   * <p><b>Prefer the pattern.</b> An override is a second place an environment's address is
   * written, and a stale one sends a whole tier's traffic to the wrong process.
   */
  Map<String, String> upstreamHosts();

  /**
   * The applications an {@code $app.$env.$domain} host name may reach directly, keyed by the {@code
   * $app} label. An entry here is what turns {@code registry.dev.localhost} from "the dev gateway"
   * into "dev's registry", and it is the on-switch: a label with no entry is refused, never
   * forwarded to the gateway.
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
     * host:port}. The same role, and the same warning, as {@link #upstreamHosts()}: it exists for a
     * developer's local process and for this repository's test suite, and a stale one sends a whole
     * application's traffic to the wrong process.
     */
    Map<String, String> hosts();
  }
}
