package eu.wohlben.qits.edge;

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
import java.util.Locale;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The edge itself: one catch-all Vert.x route that reads the Host name, picks an environment, and
 * streams each admitted exchange to a deployment-published endpoint or configured application.
 *
 * <p><b>What this does not do</b> is still most of what makes it worth having. It holds no route
 * table beyond the deployment projection and application list, rewrites no path, reads no body, and
 * serves nothing of its own but {@code /q}.
 *
 * <p><b>A name reaches a service two ways now.</b> {@code qits.edge.apps} is the configured one and
 * is a deployment fact — the machine vhosts, and the auth attributes that go with them. The
 * projection is the other: a deployment publishes the public name its service answers to, and
 * {@code <app>.<env>.<domain>} then serves that service's SPA at {@code /} and every wire route it
 * owns. They are the same kind of vhost, so a request to either is gated per request rather than
 * per plane: a machine credential, then a browser session, then the reads the deployment opened.
 *
 * <p><b>Streaming is the reason for the shape.</b> {@code vertx-http-proxy} never buffers a request
 * or response body and forwards a WebSocket upgrade by default, so the platform's interactive
 * terminals, its SSE channels, its {@code git clone}s and its OCI layer pushes all pass through
 * unchanged. A JAX-RS layer would buffer and re-encode all four.
 *
 * <p><b>Security posture.</b> The upstream host and port come from configuration or from the
 * deployment projection only. A Host name selects an <i>index into a fixed list</i> and never
 * contributes a character to an address, so there is no name a client can send that reaches a host
 * neither the deployment nor a deployed service named. An unmatched name is not an error — it is
 * the default environment.
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

  @Inject EdgeSessions sessions;

  @Inject EdgeRoutes routes;

  @Inject DeploymentProjectionBootstrap projectionBootstrap;

  private HostEnvironments hostEnvironments;

  /** One reusable proxy per configured application vhost. */
  private final Map<String, HttpProxy> appProxies = new java.util.LinkedHashMap<>();

  /**
   * Direct deployment endpoints arrive after boot, so their proxies are created lazily and reused.
   */
  private final Map<Upstream, HttpProxy> endpointProxies =
      new java.util.concurrent.ConcurrentHashMap<>();

  private HttpClient client;

  /**
   * Where one Host name goes, once the projection has had its say.
   *
   * <p>Router-local on purpose: {@link HostEnvironments} answers from configuration alone and stays
   * static and framework-free, so what a deployment published is joined on here rather than there.
   *
   * @param route the configured answer. For a name only the projection knows, its {@code app} is
   *     that label — which is what gives the request an app vhost's gate and audience.
   * @param host the published service behind the name, or null for a configured-only vhost and for
   *     the environment vhost
   */
  private record Target(HostEnvironments.Route route, EdgeRoutes.ServiceHost host) {

    /** Whether this name reaches ONE service rather than the environment's whole path space. */
    boolean service() {
      return route.toApp() || host != null;
    }

    String environment() {
      return route.environment();
    }
  }

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
        .setConnectTimeout(connectTimeoutMs);
  }

  void init(@Observes Router router) {
    hostEnvironments =
        HostEnvironments.of(
            config.environments(), config.defaultEnvironment(), config.apps().keySet());
    client = vertx.createHttpClient(proxyClientOptions(5_000));

    for (String environment : hostEnvironments.environments()) {
      // Every application, in every environment. The app entry is one pattern and the environment
      // list is the other axis, so the whole grid exists at boot and no address is built per
      // request — the same SSRF guard as the gateways: a Host name selects an index, never a
      // character of an address.
      for (String app : hostEnvironments.apps()) {
        registerApp(app + "." + environment, appUpstream(config.apps().get(app), environment));
      }
    }
    router.route().order(ROUTE_ORDER).handler(this::handle);
  }

  private void registerApp(String key, Upstream upstream) {
    appProxies.put(
        key,
        HttpProxy.reverseProxy(client)
            .origin(upstream.port(), upstream.host())
            .addInterceptor(new EdgeHeaders())
            .addInterceptor(new EdgeCacheControl()));
  }

  /** Where an unmatched Host name goes. */
  public String defaultEnvironment() {
    return hostEnvironments.defaultEnvironment();
  }

  /**
   * One configured application's upstream in one environment. Package-private and static because
   * {@code DeploymentActiveSubscriber} asks the same question of the same entry: a published host
   * that is also a configured vhost is the same service exactly when these two agree.
   */
  static Upstream appUpstream(EdgeConfig.App spec, String environment) {
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

    if (!projectionBootstrap.authoritative()) {
      // A persisted snapshot can be stale or wholly absent until the startup replay has reached
      // qits-events' confirmed head. Callers get an explicit, retryable admission refusal instead.
      request
          .response()
          .setStatusCode(503)
          .putHeader(HttpHeaders.RETRY_AFTER, "1")
          .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
          .end("edge deployment routing is catching up; retry shortly\\n");
      return;
    }

    HostEnvironments.Route named = hostEnvironments.route(authority(request));
    Target target = target(named);
    if (target == null) {
      // NOT a fall-through to the gateway. The name is app-shaped, so it was aimed at a service —
      // and no configuration and no deployment claims it. Answering here is the whole point: a
      // mistyped registry vhost must fail, not quietly reach an unauthenticated route.
      unknownApp(request, named);
      return;
    }

    if (named.toApp() && EdgeAuth.isTokenRequest(request)) {
      // The docker Bearer flow's own endpoint, advertised in the challenge below. It carries the
      // credential that BUYS a token, so it is the one path on an app vhost that cannot require
      // one. Configured vhosts only: it is the challenge's realm that names it, and only a
      // configured entry carries the auth attributes that challenge is built from.
      auth.token(request);
      return;
    }

    if (!target.service() && redirected(request, target.environment())) {
      return;
    }

    if (target.service()) {
      serviceGate(request, target);
      return;
    }

    if (sessions.enabled()) {
      // The environment vhost is the one a browser types with no application in mind, so it is the
      // one whose gate has no machine plane behind it at all.
      gate(request, target);
      return;
    }

    Future<String> checked = auth.check(target.route(), request);
    if (!checked.isComplete()) {
      // The check crossed an event-loop boundary — a JWKS fetch. Hold the inbound body until there
      // is somewhere to send it; vertx-http-proxy pauses and resumes the request itself, so handing
      // it a paused one is the safe state to be in either way.
      request.pause();
    }
    checked
        .onSuccess(rejection -> dispatch(request, target, rejection))
        .onFailure(
            failure -> {
              LOG.errorf(failure, "could not check the credential on %s", authority(request));
              // A validator that cannot answer denies. The alternative is an outage of the identity
              // provider becoming an outage of the auth gate, which is the wrong way round.
              auth.challenge(request, "the credential could not be checked");
            });
  }

  /**
   * Configuration and the projection, joined. A configured label keeps its entry — that is where
   * the audience, the anonymous reads and the token endpoint are written — and gains the published
   * service when its deployment has been flipped. A label only the projection knows is given the
   * same shape, so everything below treats the two alike.
   *
   * @return null when the name is app-shaped and nobody claims it, which is the 404
   */
  private Target target(HostEnvironments.Route named) {
    if (named.unknownApp() == null) {
      return new Target(
          named, named.toApp() ? routes.serviceHost(named.environment(), named.app()) : null);
    }
    EdgeRoutes.ServiceHost published = routes.serviceHost(named.environment(), named.unknownApp());
    return published == null
        ? null
        : new Target(
            new HostEnvironments.Route(named.environment(), named.unknownApp(), null), published);
  }

  /**
   * The two conveniences on the environment vhost, and both are keyed on projection data alone.
   *
   * <p>A person who typed or bookmarked {@code dev.example.com/ci/runs/7} is sent to {@code
   * ci.dev.example.com/runs/7}, so that a flipped service has ONE address rather than two — the SPA
   * behind it now builds every link against {@code /}. Only a navigation is moved: a fetch or a
   * socket to the old path keeps working, which is what makes the flip safe to make one service at
   * a time. An API and a management path are never moved: {@code /ci/api} is where the SPA's own
   * XHRs go, and moving one would cost it its origin.
   *
   * <p>And the environment's own name is a door rather than a page: it goes to qits-projects' host
   * once the projection knows one. Until then this answers nothing and the request takes the path
   * it always took.
   *
   * @return true when the request has been answered here
   */
  private boolean redirected(HttpServerRequest request, String environment) {
    if (request.method() != HttpMethod.GET) {
      return false;
    }
    if (request.path().equals("/")) {
      EdgeRoutes.ServiceHost projects = routes.projectsHost(environment);
      if (projects == null) {
        return false;
      }
      redirect(request, authorityOf(request).hostOrigin(projects.host()) + "/");
      return true;
    }
    if (!EdgeSessions.isNavigation(
        request.method(),
        request.getHeader(EdgeSessions.FETCH_MODE),
        request.getHeader(HttpHeaders.ACCEPT))) {
      return false;
    }
    EdgeEndpoint endpoint = routes.resolve(environment, request.path());
    if (endpoint == null) {
      return false;
    }
    EdgeRoutes.ServiceHost host = routes.applicationHost(environment, endpoint.application());
    if (host == null || !host.primaryPath().equals(endpoint.path())) {
      // Either the application has not been flipped, or this is one of its other root routes —
      // /v2, /git, /bootstrap-git. A wire route is nobody's bookmark and keeps its path.
      return false;
    }
    String rest = request.uri().substring(endpoint.path().length());
    if (below(rest, "/api") || below(rest, "/q")) {
      return false;
    }
    redirect(
        request,
        authorityOf(request).hostOrigin(host.host())
            + (rest.isEmpty() || rest.startsWith("?") ? "/" + rest : rest));
    return true;
  }

  /** Whether what is left of a path after its segment is that segment's own {@code prefix}. */
  private static boolean below(String rest, String prefix) {
    return rest.equals(prefix) || rest.startsWith(prefix + "/") || rest.startsWith(prefix + "?");
  }

  private static void redirect(HttpServerRequest request, String location) {
    request
        .response()
        .setStatusCode(302)
        .putHeader(HttpHeaders.LOCATION, location)
        // A cached redirect would outlive the projection it was derived from.
        .putHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        .end();
  }

  /**
   * The environment origin this request's own name belongs to — see {@link EnvironmentAuthority}.
   * Package-visible for the navigation document, which has to write the same origins.
   */
  EnvironmentAuthority authorityOf(HttpServerRequest request) {
    return EnvironmentAuthority.of(
        authorityWithPort(request),
        request.getHeader(EdgeHeaders.PROTO),
        request.scheme(),
        hostEnvironments.environments(),
        hostEnvironments.defaultEnvironment(),
        sessions.canonicalAuthority());
  }

  /**
   * A service vhost's gate, in the order the plan sets out: a browser session, then everything the
   * machine plane already did.
   *
   * <p><b>A cookie is only looked for when nothing else identifies the caller.</b> A machine
   * credential is a machine saying who it is and has no session, and a vhost whose reads the
   * deployment opened must keep serving a client that holds neither — which is what keeps {@code
   * docker pull} and {@code npm install} working on exactly the names they work on today.
   */
  private void serviceGate(HttpServerRequest request, Target target) {
    String cookie =
        sessions.enabled() && !EdgeAuth.carriesCredential(request)
            ? sessions.cookie(request)
            : null;
    if (cookie == null) {
      machine(request, target);
      return;
    }
    Future<EdgeSessions.Session> introspected = sessions.introspect(cookie);
    if (!introspected.isComplete()) {
      // The answer comes from idp, over a socket. Hold the inbound body until there is somewhere to
      // send it.
      request.pause();
    }
    introspected
        .onSuccess(
            session -> {
              if (session == null) {
                machine(request, target);
                return;
              }
              proxy(request, target, session);
            })
        .onFailure(
            failure -> {
              LOG.errorf(failure, "could not introspect a session for %s", authority(request));
              machine(request, target);
            });
  }

  /**
   * The machine half of a service vhost, which is the gate exactly as it stood: the deployment's
   * own exemptions first, then the credential, then the refusal. With the session gate off this is
   * the whole of a service vhost's decision, and it is unchanged.
   */
  private void machine(HttpServerRequest request, Target target) {
    if (auth.open(target.route(), request)) {
      proxy(request, target, null);
      return;
    }
    if (!EdgeAuth.carriesCredential(request)) {
      refuseService(request);
      return;
    }
    Future<String> checked = auth.checkCredential(target.route(), request);
    if (!checked.isComplete()) {
      request.pause();
    }
    checked
        .onSuccess(rejection -> dispatch(request, target, rejection))
        .onFailure(
            failure -> {
              LOG.errorf(failure, "could not check the credential on %s", authority(request));
              auth.challenge(request, "the credential could not be checked");
            });
  }

  /**
   * What a service vhost answers a caller it knows nothing about: the login page for something that
   * can render one, and the {@code WWW-Authenticate} challenge for everything else — {@code docker}
   * on {@code /v2/} above all, which acts on the realm and would give up without it.
   */
  private void refuseService(HttpServerRequest request) {
    if (sessions.enabled()
        && EdgeSessions.isNavigation(
            request.method(),
            request.getHeader(EdgeSessions.FETCH_MODE),
            request.getHeader(HttpHeaders.ACCEPT))) {
      sessions.refuse(request);
      return;
    }
    auth.challenge(request, "no bearer token");
  }

  /**
   * The gated request of the user-authentication plan, in the order the plan sets out: a machine
   * credential, then a session cookie, then an anonymous prefix, then a refusal. Step 1 — dropping
   * every inbound {@code X-Qits-*} — is {@link #proxy}'s, which is the single point any of these
   * paths can reach an upstream through.
   *
   * <p>Reached only while {@link EdgeSessions#enabled()}, so with the flag off not one line of it
   * runs and the request takes exactly the path it took before this existed.
   */
  private void gate(HttpServerRequest request, Target target) {
    if (EdgeAuth.carriesCredential(request)) {
      // CI dialing through the gateway, a curl with the workstation pair, a git push: the session
      // gate is a third acceptable credential, never a replacement for these. Checked in full even
      // though this vhost's own switch may not demand one — an unchecked Authorization header would
      // otherwise be a way past the whole gate.
      Future<String> checked = auth.checkCredential(target.route(), request);
      if (!checked.isComplete()) {
        request.pause();
      }
      checked
          .onSuccess(rejection -> dispatch(request, target, rejection))
          .onFailure(
              failure -> {
                LOG.errorf(failure, "could not check the credential on %s", authority(request));
                auth.challenge(request, "the credential could not be checked");
              });
      return;
    }

    String cookie = sessions.cookie(request);
    if (cookie == null) {
      unauthenticated(request, target);
      return;
    }
    Future<EdgeSessions.Session> introspected = sessions.introspect(cookie);
    if (!introspected.isComplete()) {
      // The same reason as the credential check above: the answer comes from idp, over a socket.
      request.pause();
    }
    introspected
        .onSuccess(
            session -> {
              if (session == null) {
                unauthenticated(request, target);
                return;
              }
              proxy(request, target, session);
            })
        .onFailure(
            failure -> {
              LOG.errorf(failure, "could not introspect a session for %s", authority(request));
              unauthenticated(request, target);
            });
  }

  /**
   * What happens to a request with no usable session: the anonymous prefixes first, then the
   * refusal.
   *
   * <p><b>The order matters and it is not the plan's.</b> The plan lists the cookie step before the
   * anonymous one, which is right for a cookie that works — but a browser holding a session idp has
   * since revoked would then be refused at {@code /idp/login} and redirected to {@code /idp/login},
   * forever. The prefix is what a caller with no usable credential is entitled to, so it answers
   * every one of them.
   */
  private void unauthenticated(HttpServerRequest request, Target target) {
    if (sessions.anonymous(request.path())) {
      proxy(request, target, null);
      return;
    }
    sessions.refuse(request);
  }

  /**
   * Proxy, or answer the challenge. Split out of {@link #handle} because it is what runs after the
   * credential check, which may have crossed an event-loop boundary to refresh a signing key.
   */
  private void dispatch(HttpServerRequest request, Target target, String rejection) {
    if (rejection != null) {
      auth.challenge(request, rejection);
      return;
    }
    // No session, so no identity: a machine's own is in the token it carried.
    proxy(request, target, null);
  }

  /**
   * <b>The one way out of this process</b>, for every route above and both transports. Which is
   * what makes the header work here rather than in three places: a request that reaches an upstream
   * has passed through this method, so what it does is done always.
   */
  private void proxy(HttpServerRequest request, Target target, EdgeSessions.Session session) {
    if (target.service() && session == null) {
      // A parent-domain browser session reaches every sibling name by browser design. This request
      // is not using one — it is a machine's, or a read the deployment opened — so the cookie is
      // removed before the service sees it; unrelated application cookies remain intact.
      EdgeHeaders.stripCookie(request.headers(), sessions.cookieName());
    }
    if (sessions.enabled() && (!target.service() || session != null)) {
      // Strip, then assert — see EdgeHeaders.applyIdentity, where both halves live in one method on
      // purpose. On the ordinary path the proxy copies these headers upstream; on an upgrade it
      // forwards this same map, so one call covers a path the interceptor chain never sees.
      EdgeHeaders.applyIdentity(request.headers(), session);
    }
    // A WebSocket upgrade short-circuits inside vertx-http-proxy before the interceptor chain is
    // installed, so the forwarded headers have to be written onto the inbound request instead. See
    // EdgeHeaders.applyForwarded.
    if (isWebSocketUpgrade(request)) {
      EdgeHeaders.applyForwarded(request.headers(), request);
    }
    EdgeRoutes.ServiceHost host = target.host();
    if (host != null) {
      // A published host serves its own service at every path that service owns, and every OTHER
      // application's declared prefix as well — which is what keeps /projects/api, /git and /v2
      // same-origin from any of them. The one route that may not travel is a bare `/`: it is the
      // catch-all of whichever application declared it, and on this name the catch-all is the
      // service the name belongs to.
      EdgeEndpoint endpoint = routes.resolve(target.environment(), request.path());
      if (endpoint != null
          && (!endpoint.path().equals("/") || endpoint.application().equals(host.application()))) {
        endpointProxy(endpoint.upstream()).handle(request);
        return;
      }
      endpointProxy(host.upstream()).handle(request);
      return;
    }
    if (target.route().toApp()) {
      // Configured, and its deployment has published no host of its own: the whole name is one
      // service exactly as it was before the projection carried any.
      appProxies.get(target.route().app() + "." + target.environment()).handle(request);
      return;
    }
    // Deployment events own environment-vhost path routing. Admission above has already proved the
    // startup replay reached qits-events' head, so no compatibility fallback may invent a route.
    EdgeEndpoint endpoint = routes.resolve(target.environment(), request.path());
    if (endpoint != null) {
      endpointProxy(endpoint.upstream()).handle(request);
      return;
    }
    unknownPath(request, target.environment());
  }

  private HttpProxy endpointProxy(Upstream upstream) {
    return endpointProxies.computeIfAbsent(
        upstream,
        address ->
            HttpProxy.reverseProxy(client)
                .origin(address.port(), address.host())
                .addInterceptor(new EdgeHeaders())
                .addInterceptor(new EdgeCacheControl()));
  }

  /**
   * Package-visible for the local navigation route: it must use the same host resolution as
   * proxying.
   */
  String environment(HttpServerRequest request) {
    return hostEnvironments.route(authority(request)).environment();
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

  private void unknownPath(HttpServerRequest request, String environment) {
    request
        .response()
        .setStatusCode(404)
        .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
        .end(
            "No active deployment endpoint in environment `"
                + environment
                + "` matches `"
                + request.path()
                + "`.\n");
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
   * The same name with its port, which is what an ORIGIN is built from: a developer's whole
   * platform is one port, so {@code http://ci.dev.localhost:8080} needs the number the request
   * carried.
   */
  private static String authorityWithPort(HttpServerRequest request) {
    HostAndPort authority = request.authority();
    if (authority == null) {
      return request.getHeader(HttpHeaders.HOST);
    }
    return authority.port() < 0 ? authority.host() : authority.host() + ":" + authority.port();
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
