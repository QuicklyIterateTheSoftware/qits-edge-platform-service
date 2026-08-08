package eu.wohlben.qits.edge;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.runtime.RouteConstants;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.httpproxy.HttpProxy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The edge itself: one catch-all Vert.x route that reads the Host name, picks an environment, and
 * streams the exchange to that environment's gateway verbatim.
 *
 * <p><b>What this does not do</b> is most of what makes it worth having. It does not look at paths,
 * does not hold a route table beyond the environment list, does not authenticate, does not strip or
 * inject identity headers, and serves nothing of its own but {@code /q}. Every one of those belongs
 * to the environment gateway one hop further in, which already does them and is already tested for
 * them; a second implementation here would be a second answer to the same question.
 *
 * <p><b>Streaming is the reason for the shape.</b> {@code vertx-http-proxy} never buffers a request
 * or response body and forwards a WebSocket upgrade by default, so the platform's interactive
 * terminals, its SSE channels, its {@code git clone}s and its OCI layer pushes all pass through
 * unchanged. A JAX-RS layer would buffer and re-encode all four.
 *
 * <p><b>Security posture.</b> The upstream host and port come from configuration only. A Host name
 * selects an <i>index into a fixed list</i> and never contributes a character to an address, so
 * there is no name a client can send that reaches a host the deployment did not name. An unmatched
 * name is not an error — it is the default environment.
 */
@ApplicationScoped
public class EdgeRouter {

  /**
   * After everything this application registers normally, which for the edge means after {@code
   * /q}. Nothing is layered behind it: the edge has no static content and no SPA, so this route is
   * genuinely last and a request that reaches it is proxied.
   *
   * <p>{@link #handle} also passes {@code /q} to {@code next()} explicitly rather than relying on
   * this number. Route order decides who runs first; the explicit skip decides who <i>answers</i>,
   * and it keeps the health surface local even if a future Quarkus moved its own routes.
   */
  public static final int ROUTE_ORDER = RouteConstants.ROUTE_ORDER_AFTER_DEFAULT;

  private static final Logger LOG = Logger.getLogger(EdgeRouter.class);

  @Inject Vertx vertx;

  @Inject EdgeConfig config;

  @ConfigProperty(name = "quarkus.http.non-application-root-path", defaultValue = "/q")
  String nonApplicationRootPath;

  private HostEnvironments hostEnvironments;

  /** One reusable proxy per environment — the origin is fixed, so nothing is built per request. */
  private final Map<String, HttpProxy> proxies = new LinkedHashMap<>();

  /** The resolved addresses, kept for the startup log and the readiness payload. */
  private final Map<String, Upstream> upstreams = new LinkedHashMap<>();

  private HttpClient client;

  void init(@Observes Router router) {
    hostEnvironments = HostEnvironments.of(config.environments(), config.defaultEnvironment());
    client =
        vertx.createHttpClient(
            new HttpClientOptions()
                .setKeepAlive(true)
                // Vert.x pools per origin and defaults to FIVE connections behind an unbounded wait
                // queue. Every request for an environment shares one origin here, so the default
                // would make a single `docker push` — up to five concurrent layer uploads, each
                // holding its connection for minutes — starve that whole environment with nothing
                // logged to say why. The same number, for the same reason, as qits-gateway's.
                .setMaxPoolSize(64)
                // Stated rather than inherited. Zero, no client-side idle timeout, is already the
                // default and has to stay: quarkus.http.idle-timeout keeps the inbound half of a
                // long exchange alive, and a timeout here would sever exactly what that exists for
                // — a terminal socket, an SSE channel, a slow layer push.
                .setIdleTimeout(0));

    for (String environment : hostEnvironments.environments()) {
      Upstream upstream = resolve(environment);
      upstreams.put(environment, upstream);
      proxies.put(
          environment,
          HttpProxy.reverseProxy(client)
              .origin(upstream.port(), upstream.host())
              .addInterceptor(new EdgeHeaders()));
    }
    router.route().order(ROUTE_ORDER).handler(this::handle);
  }

  /** The resolved environment to upstream map — the readiness payload and the startup log. */
  public Map<String, Upstream> upstreams() {
    return Map.copyOf(upstreams);
  }

  /** Where an unmatched Host name goes. */
  public String defaultEnvironment() {
    return hostEnvironments.defaultEnvironment();
  }

  void logTable(@Observes StartupEvent ignored) {
    upstreams.forEach(
        (environment, upstream) ->
            LOG.infof(
                "environment %-12s -> %s%s",
                environment,
                upstream,
                environment.equals(hostEnvironments.defaultEnvironment()) ? "   (default)" : ""));
  }

  private Upstream resolve(String environment) {
    String override = config.upstreamHosts().get(environment);
    String address =
        override != null && !override.isBlank()
            ? override
            : config.upstreamHostPattern().replace("{env}", environment);
    return Upstream.parse(address, config.upstreamPort());
  }

  private void handle(RoutingContext rc) {
    HttpServerRequest request = rc.request();
    String path = request.path();
    if (path.equals(nonApplicationRootPath) || path.startsWith(nonApplicationRootPath + "/")) {
      // The edge's own management surface — health above all — is answered by this process whatever
      // the Host name says. It is the one thing an orchestrator must be able to ask the edge about
      // itself rather than about an environment behind it.
      rc.next();
      return;
    }

    String environment = hostEnvironments.resolve(authority(request));
    // A WebSocket upgrade short-circuits inside vertx-http-proxy before the interceptor chain is
    // installed, so the forwarded headers have to be written onto the inbound request instead. See
    // EdgeHeaders.applyForwarded.
    if (isWebSocketUpgrade(request)) {
      EdgeHeaders.applyForwarded(request.headers(), request);
    }
    proxies.get(environment).handle(request);
  }

  /**
   * The name the client asked for. {@code authority()} is the one accessor that answers for
   * HTTP/1.1 and HTTP/2 alike — the second has no Host header, only a {@code :authority}
   * pseudo-header — and it falls back to the raw header for a request that carried neither in a
   * form Vert.x parsed.
   */
  private static String authority(HttpServerRequest request) {
    HostAndPort authority = request.authority();
    return authority != null ? authority.host() : request.getHeader(HttpHeaders.HOST);
  }

  /**
   * RFC 6455's three conditions: a GET, {@code Upgrade: websocket}, and a {@code Connection} naming
   * the upgrade — a contains rather than an equals, because that header may carry other tokens.
   *
   * <p>Spelled out rather than reusing {@code io.vertx.core.http.impl.HttpUtils}, which is internal
   * API and has moved between Vert.x releases.
   */
  private static boolean isWebSocketUpgrade(HttpServerRequest request) {
    if (request.method() != HttpMethod.GET) {
      return false;
    }
    String upgrade = request.getHeader(HttpHeaders.UPGRADE);
    String connection = request.getHeader(HttpHeaders.CONNECTION);
    return upgrade != null
        && upgrade.equalsIgnoreCase("websocket")
        && connection != null
        && connection.toLowerCase(Locale.ROOT).contains("upgrade");
  }
}
