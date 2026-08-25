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
 * serves nothing of its own but {@code /q} and {@code /main-navigation}.
 *
 * <p><b>A name reaches a service two ways now.</b> {@code qits.edge.apps} is the configured one and
 * is a deployment fact — the machine vhosts, and the auth attributes that go with them. The
 * projection is the other: a deployment publishes the public name its service answers to, and
 * {@code <app>.<env>.<domain>} then serves that service's SPA at {@code /} and every wire route it
 * owns. They are the same kind of vhost, so a request to either is gated per request rather than
 * per plane: a machine credential, then a browser session, then the reads the deployment opened.
 *
 * <p><b>The environment's own name is a door, and a door serves nothing.</b> {@code <env>.<domain>}
 * answers {@code GET /} with a redirect to the projects host and 404s every other path — see {@link
 * #door}. It routes nothing, gates nothing and proxies nothing, so a service is reachable on its
 * own name alone.
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

    /** Whether this name reaches a service. False is the environment vhost, which is the door. */
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
    Target target = target(named, authority(request));
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

    if (!target.service()) {
      door(request, target.environment());
      return;
    }

    serviceGate(request, target);
  }

  /**
   * Configuration and the projection, joined. A configured label keeps its entry — that is where
   * the audience, the anonymous reads and the token endpoint are written — and gains the published
   * service when its deployment has been flipped. A label only the projection knows is given the
   * same shape, so everything below treats the two alike.
   *
   * @return null when the name is app-shaped and nobody claims it, which is the 404
   */
  private Target target(HostEnvironments.Route named, String host) {
    if (named.unknownApp() == null) {
      if (named.toApp()) {
        return new Target(named, routes.serviceHost(named.environment(), named.app()));
      }
      // $app.$domain, for a name the configuration does not know. The environment label is optional
      // for the DEFAULT environment — the apex is its door — so a first label a deployment
      // published there reads as that service. An unknown label is untouched by this and still goes
      // to the default environment, which is what keeps a mistyped or decommissioned name harmless.
      String candidate = canonicalApex(host) ? null : hostEnvironments.defaultEnvironmentApp(host);
      String environment = hostEnvironments.defaultEnvironment();
      EdgeRoutes.ServiceHost published =
          candidate == null ? null : routes.serviceHost(environment, candidate);
      return published == null
          ? new Target(named, null)
          : new Target(new HostEnvironments.Route(environment, candidate, null), published);
    }
    EdgeRoutes.ServiceHost published = routes.serviceHost(named.environment(), named.unknownApp());
    return published == null
        ? null
        : new Target(
            new HostEnvironments.Route(named.environment(), named.unknownApp(), null), published);
  }

  /**
   * Whether this name IS the configured door, in which case it names no application.
   *
   * <p>{@code example.com} and {@code ci.localhost} are the same shape and no name tells them
   * apart, so the apex would otherwise offer its own first label as an application. It is the one
   * name a deployment always states, so it is also the one the edge can rule out.
   */
  private boolean canonicalApex(String host) {
    String canonical = sessions.canonicalAuthority();
    if (canonical == null || host == null) {
      return false;
    }
    int colon = canonical.lastIndexOf(':');
    String name =
        colon > 0 && canonical.indexOf(':') == colon ? canonical.substring(0, colon) : canonical;
    return name.equalsIgnoreCase(host.strip());
  }

  /**
   * The environment's own name, which is a door and nothing else.
   *
   * <p><b>It serves no path.</b> Every service is reached on its own name, so a route, an API, a
   * wire protocol or a login page offered here would be a second address for something that already
   * has one — and a second address is a second origin, a second cookie scope and a second thing to
   * keep in step. There is no gate either, because there is nothing behind it to gate.
   *
   * <p>What is left is the one thing a door is for: {@code GET /} goes to qits-projects' host, so
   * an anonymous visitor typing the environment's name lands on the login through the host that
   * owns it. The edge's own {@code /q} and {@code /main-navigation} are answered before this.
   */
  private void door(HttpServerRequest request, String environment) {
    EdgeRoutes.ServiceHost projects = routes.projectsHost(environment);
    boolean read = request.method() == HttpMethod.GET || request.method() == HttpMethod.HEAD;
    if (read && request.path().equals("/") && projects != null) {
      redirect(request, authorityOf(request).hostOrigin(projects.host()) + "/");
      return;
    }
    // Once per request, at INFO: this is how anything still dialling the door is found. The door
    // answered the whole platform until the per-service hosts landed, so a caller that has not
    // moved is a bug somewhere else and needs a name in a log.
    LOG.infof(
        "the door serves nothing: %s %s on %s",
        request.method(), request.path(), authority(request));
    request
        .response()
        .setStatusCode(404)
        .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
        .end(
            "This name is the environment door and serves nothing. Every service is on its own"
                + " name, `<app>."
                + authorityOf(request).authority()
                + "`.\n"
                + (projects == null
                    ? ""
                    : "Start at " + authorityOf(request).hostOrigin(projects.host()) + "\n"));
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
   *
   * <p>A caller with no usable session then meets {@link #noSession}, which is where this name's
   * own anonymous prefixes are served.
   */
  private void serviceGate(HttpServerRequest request, Target target) {
    String cookie =
        sessions.enabled() && !EdgeAuth.carriesCredential(request)
            ? sessions.cookie(request)
            : null;
    if (cookie == null) {
      noSession(request, target);
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
                noSession(request, target);
                return;
              }
              proxy(request, target, session);
            })
        .onFailure(
            failure -> {
              LOG.errorf(failure, "could not introspect a session for %s", authority(request));
              noSession(request, target);
            });
  }

  /**
   * What a service vhost answers a caller with no usable session: the anonymous prefixes on the
   * name that OWNS them, then the machine gate as it stood.
   *
   * <p><b>One host serves them, not every host.</b> {@code /idp/} is anonymous on {@code
   * idp.<env>.<domain>}, because that is where the login page is now — and a login page nobody can
   * reach without a session redirects to itself forever. Every other name still refuses the prefix,
   * so this opens one service rather than a path on the whole environment.
   *
   * <p>Ordered ahead of the refusal for the same reason as {@link #unauthenticated}: a browser
   * holding a session idp has since revoked must reach the login page too, not only one carrying no
   * cookie at all.
   */
  private void noSession(HttpServerRequest request, Target target) {
    if (sessions.enabled()
        && !EdgeAuth.carriesCredential(request)
        && sessions.anonymous(request.path())
        && target.host() != null) {
      EdgeEndpoint endpoint = routes.resolve(target.environment(), request.path());
      if (endpoint != null && endpoint.application().equals(target.host().application())) {
        // Nobody vouched for this caller, so no identity may arrive upstream.
        EdgeHeaders.applyIdentity(request.headers(), null);
        proxy(request, target, null);
        return;
      }
    }
    machine(request, target);
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
      refuseService(request, target);
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
   * What a service vhost answers a caller it knows nothing about: the login page for a navigation,
   * a 401 naming the login page for any other request a BROWSER made, and the {@code
   * WWW-Authenticate} challenge for everything else — {@code docker} on {@code /v2/} above all,
   * which acts on the realm and would give up without it.
   *
   * <p>A browser is told apart by {@code Sec-Fetch-Mode}, which every current browser stamps and no
   * machine client sends. A logged-out tab's background fetch must not meet a {@code Basic}
   * challenge: the browser would answer it with its own credential dialog.
   */
  private void refuseService(HttpServerRequest request, Target target) {
    String fetchMode = request.getHeader(EdgeSessions.FETCH_MODE);
    if (sessions.enabled()
        && (fetchMode != null
            || EdgeSessions.isNavigation(
                request.method(), fetchMode, request.getHeader(HttpHeaders.ACCEPT)))) {
      sessions.refuse(request, loginOrigin(request, target.environment()));
      return;
    }
    auth.challenge(request, "no bearer token");
  }

  /**
   * Where the login page is for this request: the host of whichever deployment owns the login path,
   * written against this request's own environment origin.
   *
   * <p><b>The login moved off the door with every other service.</b> idp publishes {@code idp}, so
   * the page is at {@code https://idp.example.com/idp/login}. The canonical origin cannot follow
   * it: it is also the authority the default environment's names are derived from — see {@link
   * #canonicalApex} and {@link EnvironmentAuthority} — so it stays the door and is only the
   * fallback here.
   *
   * <p>idp is a PLATFORM service, deployed once. So an environment that owns no route for the login
   * path asks the default environment before giving up.
   *
   * @return the origin, or null when no deployment owns the login path anywhere — the canonical
   *     origin then answers, exactly as it did before any host was published
   */
  private String loginOrigin(HttpServerRequest request, String environment) {
    String published = publishedLoginOrigin(request, environment);
    if (published != null) {
      return published;
    }
    String fallback = hostEnvironments.defaultEnvironment();
    return fallback.equals(environment) ? null : publishedLoginOrigin(request, fallback);
  }

  /** One environment's answer: who owns the login path there, and the name they publish. */
  private String publishedLoginOrigin(HttpServerRequest request, String environment) {
    EdgeEndpoint endpoint = routes.resolve(environment, sessions.loginPath());
    if (endpoint == null) {
      return null;
    }
    EdgeRoutes.ServiceHost host = routes.applicationHost(environment, endpoint.application());
    return host == null ? null : authorityOf(request).hostOrigin(host.host());
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
    if (session == null) {
      // A parent-domain browser session reaches every sibling name by browser design. This request
      // is not using one — it is a machine's, or a read the deployment opened — so the cookie is
      // removed before the service sees it; unrelated application cookies remain intact.
      EdgeHeaders.stripCookie(request.headers(), sessions.cookieName());
    } else {
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
      EdgeEndpoint endpoint = routes.resolve(target.environment(), request.path());
      if (endpoint != null && travels(target, endpoint, host)) {
        endpointProxy(endpoint.upstream()).handle(request);
        return;
      }
      endpointProxy(host.upstream()).handle(request);
      return;
    }
    // Only a service target reaches this method, and a service with no published host is a
    // CONFIGURED vhost: the whole name is one service, exactly as it was before the projection
    // carried any.
    appProxies.get(target.route().app() + "." + target.environment()).handle(request);
  }

  /**
   * Whether another application's route means the same thing on this name.
   *
   * <p><b>Its PRIMARY route does</b> — {@code /projects}, {@code /workspaces}, {@code /ci} are what
   * each of those applications is known by, so an SPA on any host reads {@code /projects/api}
   * same-origin and no page needs CORS.
   *
   * <p><b>Its other routes do not.</b> {@code /v2}, {@code /git}, {@code /bootstrap-git} are wire
   * protocols whose names several services legitimately answer: qits-artifacts and the pull-through
   * mirror both speak {@code /v2}, and only one of them can own that path in a projection whose
   * paths are unique per environment. Routing it everywhere would send {@code mirror.dev/v2/} at
   * the registry and break the mirror. So a secondary route falls through to the service whose name
   * this is, exactly like a path nobody declared — and it is reached on its OWNER's name, which is
   * the only place it now exists.
   *
   * <p>A bare {@code /} never travels either, whether or not it is somebody's primary route: it is
   * the catch-all of whichever application declared it, and on this name the catch-all is the
   * service the name belongs to.
   */
  private boolean travels(Target target, EdgeEndpoint endpoint, EdgeRoutes.ServiceHost host) {
    if (endpoint.application().equals(host.application())) {
      return true;
    }
    if (endpoint.path().equals("/")) {
      return false;
    }
    return endpoint.path().equals(routes.primaryPath(target.environment(), endpoint.application()));
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
