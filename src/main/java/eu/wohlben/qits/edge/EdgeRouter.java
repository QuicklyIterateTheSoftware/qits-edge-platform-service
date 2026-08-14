package eu.wohlben.qits.edge;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.http.runtime.RouteConstants;
import io.vertx.core.Future;
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
 * <p><b>What this does not do</b> is still most of what makes it worth having. It does not look at
 * paths, does not hold a route table beyond the environment and application lists, does not strip
 * or inject identity headers, and serves nothing of its own but {@code /q}. Those belong to the
 * environment gateway one hop further in, which already does them and is already tested for them; a
 * second implementation here would be a second answer to the same question.
 *
 * <p><b>Two things it does now do</b>, both host-shaped rather than path-shaped. An {@code
 * $app.$env.$domain} name reaches a configured service directly instead of that environment's
 * gateway, and such a name is authenticated here — see {@link EdgeAuth}. Nothing about either is a
 * path decision: the app label picks a whole upstream, and the auth gate is per vhost.
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

  @Inject EdgeAuth auth;

  private HostEnvironments hostEnvironments;

  /**
   * One reusable proxy per upstream — the origin is fixed, so nothing is built per request. Keyed
   * by the environment name for a gateway and by {@code app.env} for an application, which is the
   * host name's own spelling and so needs no second lookup table.
   */
  private final Map<String, HttpProxy> proxies = new LinkedHashMap<>();

  /** The resolved addresses, kept for the startup log and the readiness payload. */
  private final Map<String, Upstream> upstreams = new LinkedHashMap<>();

  private HttpClient client;

  /**
   * The proxy client's options, built here rather than inline so the values below can be asserted
   * without booting the application — see {@code EdgeProxyClientOptionsTest}.
   *
   * <p>The two timeouts point in opposite directions on purpose, and both are load-bearing.
   */
  static HttpClientOptions proxyClientOptions(int connectTimeoutMs) {
    return new HttpClientOptions()
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
        .setIdleTimeout(0)
        // The OTHER timeout, and not a contradiction of the line above: this one bounds only the
        // wait for a TCP connection, before there is an exchange to keep alive. Vert.x defaults to
        // 60s, and under swarm a gateway's name resolves to a virtual IP that exists before any
        // task is healthy — so a connection to a starting gateway is dropped rather than refused,
        // and every request to that environment hung for a full minute before the 502. See
        // EdgeConfig.connectTimeoutMs.
        .setConnectTimeout(connectTimeoutMs);
  }

  void init(@Observes Router router) {
    hostEnvironments =
        HostEnvironments.of(
            config.environments(), config.defaultEnvironment(), config.apps().keySet());
    client = vertx.createHttpClient(proxyClientOptions(config.connectTimeoutMs()));

    for (String environment : hostEnvironments.environments()) {
      register(environment, resolve(environment));
      // Every application, in every environment. The app entry is one pattern and the environment
      // list is the other axis, so the whole grid exists at boot and no address is built per
      // request — the same SSRF guard as the gateways: a Host name selects an index, never a
      // character of an address.
      for (String app : hostEnvironments.apps()) {
        register(app + "." + environment, resolveApp(app, environment));
      }
    }
    router.route().order(ROUTE_ORDER).handler(this::handle);
  }

  private void register(String key, Upstream upstream) {
    upstreams.put(key, upstream);
    proxies.put(
        key,
        HttpProxy.reverseProxy(client)
            .origin(upstream.port(), upstream.host())
            .addInterceptor(new EdgeHeaders()));
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
        (name, upstream) ->
            LOG.infof(
                "%-14s -> %s%s",
                name,
                upstream,
                name.equals(hostEnvironments.defaultEnvironment()) ? "   (default)" : ""));
  }

  private Upstream resolve(String environment) {
    String override = config.upstreamHosts().get(environment);
    String address =
        override != null && !override.isBlank()
            ? override
            : config.upstreamHostPattern().replace("{env}", environment);
    return Upstream.parse(address, config.upstreamPort());
  }

  private Upstream resolveApp(String app, String environment) {
    EdgeConfig.App spec = config.apps().get(app);
    String override = spec.hosts().get(environment);
    String address =
        override != null && !override.isBlank()
            ? override
            : spec.hostPattern().replace("{env}", environment);
    return Upstream.parse(address, spec.port());
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

    HostEnvironments.Route route = hostEnvironments.route(authority(request));
    if (route.unknownApp() != null) {
      // NOT a fall-through to the gateway. The name is app-shaped, so it was aimed at a service —
      // and the gateway is the hop that does not authenticate these. Answering here is the whole
      // point: a mistyped registry vhost must fail, not quietly reach an unauthenticated route.
      unknownApp(request, route);
      return;
    }

    if (route.toApp() && EdgeAuth.isTokenRequest(request)) {
      // The docker Bearer flow's own endpoint, advertised in the challenge below. It carries the
      // credential that BUYS a token, so it is the one path on an app vhost that cannot require
      // one.
      auth.token(request);
      return;
    }

    Future<String> checked = auth.check(route, request);
    if (!checked.isComplete()) {
      // The check crossed an event-loop boundary — a JWKS fetch. Hold the inbound body until there
      // is somewhere to send it; vertx-http-proxy pauses and resumes the request itself, so handing
      // it a paused one is the safe state to be in either way.
      request.pause();
    }
    checked
        .onSuccess(rejection -> dispatch(request, route, rejection))
        .onFailure(
            failure -> {
              LOG.errorf(failure, "could not check the credential on %s", authority(request));
              // A validator that cannot answer denies. The alternative is an outage of the identity
              // provider becoming an outage of the auth gate, which is the wrong way round.
              auth.challenge(request, "the credential could not be checked");
            });
  }

  /**
   * Proxy, or answer the challenge. Split out of {@link #handle} because it is what runs after the
   * credential check, which may have crossed an event-loop boundary to refresh a signing key.
   */
  private void dispatch(HttpServerRequest request, HostEnvironments.Route route, String rejection) {
    if (rejection != null) {
      auth.challenge(request, rejection);
      return;
    }
    // A WebSocket upgrade short-circuits inside vertx-http-proxy before the interceptor chain is
    // installed, so the forwarded headers have to be written onto the inbound request instead. See
    // EdgeHeaders.applyForwarded.
    if (isWebSocketUpgrade(request)) {
      EdgeHeaders.applyForwarded(request.headers(), request);
    }
    proxies
        .get(route.toApp() ? route.app() + "." + route.environment() : route.environment())
        .handle(request);
  }

  private void unknownApp(HttpServerRequest request, HostEnvironments.Route route) {
    request
        .response()
        .setStatusCode(404)
        .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
        .end(
            "`"
                + route.unknownApp()
                + "` is not an application this edge routes. Configured: "
                + hostEnvironments.apps()
                + " — the environment `"
                + route.environment()
                + "` was read from the name and is fine.\n");
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
