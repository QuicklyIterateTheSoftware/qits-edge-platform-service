package eu.wohlben.qits.edge;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.List;
import java.util.Map;

/**
 * The edge's whole configuration surface: which environments exist, which one is the fallback, and
 * how an environment name becomes an upstream address.
 *
 * <p>There is no route table and no path knowledge here, deliberately. The edge demultiplexes by
 * <b>host name</b> only, and everything it needs to do that is an environment name — so a new
 * environment is one entry in {@link #environments()} and nothing else. Paths, services and
 * segments are the environment gateway's business one hop further in.
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
}
