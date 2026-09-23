package eu.wohlben.qits.edge;

import io.quarkus.vertx.http.runtime.RouteConstants;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.httpproxy.HttpProxy;
import io.vertx.httpproxy.OriginRequestProvider;
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
 * <p><b>A door serves nothing, and there are three of them.</b> The apex, a project's own name
 * {@code <project>.<domain>}, and an environment's name inside a project {@code
 * <env>.<project>.<domain>} each answer {@code GET /} with a redirect to the projects host and 404
 * every other path — see {@link #door}. They route nothing, gate nothing and proxy nothing, so a
 * service is reachable on its own name alone.
 *
 * <p><b>The grammar is read right to left and the project label is mandatory.</b> {@code
 * <app>[.<env>].<project>.<domain>} — see {@link HostEnvironments}, which holds the whole of it.
 * What is joined on here is what a deployment published, which is the one thing that class cannot
 * know, and the apex is no longer rescued here at all: the domain is stated, so the reading finds
 * it positionally like every other name.
 *
 * <p><b>Streaming is the reason for the shape.</b> {@code vertx-http-proxy} never buffers a request
 * or response body, so the platform's SSE channels, its {@code git clone}s and its OCI layer pushes
 * all pass through unchanged; a WebSocket upgrade — every interactive terminal — is spliced by
 * {@link EdgeWebSocketUpgrade}, the edge's own path, for the reasons written there. A JAX-RS layer
 * would buffer and re-encode all four.
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

  /**
   * The live project projection, which is a routing input as well as a certificate one: every
   * served name carries a project slug, and only this knows which slugs exist and which of them
   * have a tier of environments under them.
   */
  @Inject EdgeProjects projects;

  /**
   * Where the stated domain comes from — see {@link #domain}. The certificate's domain is the same
   * value read for the same reason: it is the one name the whole estate is inside.
   */
  @Inject AcmeConfig acme;

  @Inject DeploymentProjectionBootstrap projectionBootstrap;

  /**
   * Whether the project set above is complete. An unknown project label is the one reading a later
   * frame can still change, so while this is false such a name is a retryable 503 rather than a
   * 404.
   */
  @Inject ProjectSansBootstrap projectSans;

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
   *     a door — the environment's own name, or a project's
   */
  private record Target(HostEnvironments.Route route, EdgeRoutes.ServiceHost host) {

    /** Whether this name reaches a service. False is a door, which serves nothing. */
    boolean service() {
      return route.toApp() || host != null;
    }

    String environment() {
      return route.environment();
    }
  }

  /**
   * How long a request may wait for a pool slot to an origin before it fails instead. Distinct from
   * the TCP connect timeout: that one bounds reaching a live upstream, this one bounds queueing
   * behind a full pool — {@code RequestOptions.setConnectTimeout} covers the whole acquisition,
   * wait included. Generous, because a queued request behind a real burst (a {@code docker push}
   * holding many slots for minutes) used to wait indefinitely and win; but bounded, because an
   * indefinitely hanging vhost with nothing logged is how a leak stayed invisible for a day. See
   * {@code EdgeWebSocketUpgrade} for the leak this backstops.
   */
  static final int ACQUIRE_TIMEOUT_MS = 30_000;

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
        // The wait queue is bounded too, where Vert.x defaults to unbounded. An exhausted pool
        // used to queue every further request forever — the whole vhost hung, nothing logged.
        // Beyond this depth callers get an immediate failure, which the proxy answers as a 502;
        // the acquisition timeout on each origin request bounds the wait of those still queued.
        .setMaxWaitQueueSize(256)
        // Stated rather than inherited. Zero, no client-side idle timeout, is already the
        // default and has to stay: quarkus.http.idle-timeout keeps the inbound half of a
        // long exchange alive, and a timeout here would sever exactly what that exists for
        // — a terminal socket, an SSE channel, a slow layer push.
        .setIdleTimeout(0)
        .setConnectTimeout(connectTimeoutMs);
  }

  /**
   * One origin request, as both transports acquire it: the fixed address — never a character of a
   * client's — and the acquisition bound. Static for the same reason as {@link
   * #proxyClientOptions}: {@code EdgeProxyClientOptionsTest} asserts it without a boot.
   */
  static RequestOptions originRequestOptions(Upstream upstream) {
    return new RequestOptions()
        .setServer(SocketAddress.inetSocketAddress(upstream.port(), upstream.host()))
        .setConnectTimeout(ACQUIRE_TIMEOUT_MS);
  }

  private EdgeWebSocketUpgrade webSocketUpgrade;

  void init(@Observes Router router) {
    hostEnvironments =
        HostEnvironments.of(
            config.environments(),
            config.defaultEnvironment(),
            config.apps().keySet(),
            domain(acme.domain(), sessions.canonicalAuthority()));
    client = vertx.createHttpClient(proxyClientOptions(5_000));
    webSocketUpgrade = new EdgeWebSocketUpgrade(client);

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
    appProxies.put(key, reverseProxy(upstream));
  }

  private HttpProxy reverseProxy(Upstream upstream) {
    return HttpProxy.reverseProxy(client)
        .origin(origin(upstream))
        .addInterceptor(new EdgeHeaders())
        .addInterceptor(new EdgeCacheControl());
  }

  /**
   * {@code .origin(port, host)} with two additions the built-in provider lacks: the acquisition
   * bound of {@link #originRequestOptions}, and a log line. An exhausted pool used to fail with
   * nothing anywhere naming the origin that was full — the proxy answers the caller a 502 either
   * way, but the operator reads this.
   */
  private OriginRequestProvider origin(Upstream upstream) {
    return context ->
        client
            .request(originRequestOptions(upstream))
            .onFailure(failure -> LOG.warnf("no upstream connection to %s: %s", upstream, failure));
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

    if (isWebSocketUpgrade(request)) {
      // Paused on arrival, before the first await below, and this is load-bearing: Quarkus hands
      // over a ResumingRequestWrapper that resumes the underlying request as soon as anything
      // registers a handler without pausing first — after which a handshake's GET, having no
      // body, is read to its end. An upgrade must reach EdgeWebSocketUpgrade unread; a consumed
      // request is what used to throw mid-upgrade in the proxy and leak the upstream connection.
      // Every non-upgrade answer below ends a paused, bodyless request, which is harmless.
      request.pause();
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

    HostEnvironments.Route named =
        hostEnvironments.route(
            authority(request), projects.projects(), routes.landingEnvironments());
    if (named.reading() == HostEnvironments.Reading.UNKNOWN_PROJECT) {
      // The one reading a later frame can still change, and the only one the catch-up barrier
      // holds back: every other label is read by position, so nothing the projection learns moves
      // it. While the barrier is up this name gets a retryable 503 rather than a 404 about a
      // project that may well exist.
      if (!projectSans.authoritative()) {
        projectsCatchingUp(request);
      } else {
        unknownProject(request, named);
      }
      return;
    }
    if (named.reading() == HostEnvironments.Reading.UNREADABLE
        || named.reading() == HostEnvironments.Reading.RESERVED_LABEL) {
      unreadable(request, named);
      return;
    }
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

    if (!target.service()) {
      door(request, target.route());
      return;
    }

    serviceGate(request, target);
  }

  private void projectsCatchingUp(HttpServerRequest request) {
    LOG.infof(
        "the project projection is behind, so %s is answered 503 rather than 404",
        authority(request));
    request
        .response()
        .setStatusCode(503)
        .putHeader(HttpHeaders.RETRY_AFTER, "1")
        .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
        .end(
            "This name carries a project label and the edge is still reading the project log;"
                + " retry shortly.\n");
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
    if (named.toApp()) {
      return new Target(named, routes.serviceHost(named.environment(), named.app()));
    }
    if (named.unknownApp() != null) {
      EdgeRoutes.ServiceHost published =
          routes.serviceHost(named.environment(), named.unknownApp());
      return published == null
          ? null
          : new Target(
              new HostEnvironments.Route(
                  named.environment(),
                  named.unknownApp(),
                  null,
                  named.project(),
                  HostEnvironments.Reading.APP),
              published);
    }
    // A door, and a door is never joined on: the grammar gives a published service name an APP
    // position of its own, so nothing a deployment publishes can occupy a project's or an
    // environment's name. That join existed because a slug and a published name used to be the same
    // single label in front of an environment, which the project tier retired.
    return new Target(named, null);
  }

  /**
   * The stated domain every served name is read from the right of.
   *
   * <p>It cannot be derived from a host name — {@code example.co.uk} is two labels of domain and
   * {@code localhost} is one — so it is a value this deployment already carries twice. The
   * certificate's own domain is the first source and the honest one: the edge orders the names
   * inside {@code qits.edge.acme.domain}, so that is by construction the domain the estate lives
   * in. A local clone runs with ACME off and no domain at all, and there the canonical origin's
   * authority is the same value — {@code localhost}.
   *
   * <p>Static so it can be asserted without a boot; see {@code EdgeRouterNamesTest}.
   */
  static String domain(java.util.Optional<String> acmeDomain, String canonicalAuthority) {
    String configured = acmeDomain.orElse("").strip();
    return configured.isEmpty() ? EnvironmentAuthority.name(canonicalAuthority) : configured;
  }

  /**
   * A door, which is a name that serves nothing: the apex, a project's own name, and an
   * environment's name inside a project.
   *
   * <p><b>It serves no path.</b> Every service is reached on its own name, so a route, an API, a
   * wire protocol or a login page offered here would be a second address for something that already
   * has one — and a second address is a second origin, a second cookie scope and a second thing to
   * keep in step. There is no gate either, because there is nothing behind it to gate.
   *
   * <p>What is left is the one thing a door is for: {@code GET /} goes to qits-projects' host, so
   * an anonymous visitor typing the name lands on the login through the host that owns it. A
   * project's door sends them to the same place — the edge knows a slug is a label and nothing
   * else, so inventing a path for it here would be inventing qits-projects' routing table. The
   * edge's own {@code /q} and {@code /main-navigation} are answered before this.
   *
   * <p><b>A door inside no project has nowhere to send anybody.</b> qits-projects is reached at
   * {@code projects.<project>.<domain>} like every other application, so composing that name needs
   * a project label — and the apex, an address literal and a name outside the domain carry none.
   * When the canonical origin supplies none either, this answers the 404 with the grammar and no
   * {@code Start at} line, rather than redirecting to a name that would 404 one hop later.
   *
   * @param named the door's own reading — the apex, a project's name, or an environment's name
   *     inside a project. Which of the three decides only the sentence; all three serve nothing.
   */
  private void door(HttpServerRequest request, HostEnvironments.Route named) {
    String environment = named.environment();
    String startAt;
    if (named.reading() == HostEnvironments.Reading.PROJECT_LANDING_DOOR) {
      // This project's root IS a deployment, and it runs one per environment like every other
      // application — so the bare project name cannot be one of them and sends a browser to the
      // DEFAULT environment's. EnvironmentAuthority composes that name from this same reading: the
      // innermost door of an env-supporting project is its environment's.
      startAt = authorityOf(request).origin();
    } else {
      EdgeRoutes.ServiceHost projectsHost = routes.projectsHost(environment);
      // Null when this door is inside no project — the apex, an address, a name outside the domain
      // — and the canonical origin names none either. Every application address carries a project
      // label now, so there is simply no name to send anybody to; see EnvironmentAuthority.
      startAt = projectsHost == null ? null : authorityOf(request).hostOrigin(projectsHost.host());
    }
    boolean read = request.method() == HttpMethod.GET || request.method() == HttpMethod.HEAD;
    if (read && request.path().equals("/") && startAt != null) {
      redirect(request, startAt + "/");
      return;
    }
    // Once per request, at INFO: this is how anything still dialling a door is found. The door
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
            doorBody(named, hostEnvironments.domain())
                + (startAt == null ? "" : "Start at " + startAt + "\n"));
  }

  /**
   * What a door says, which is the grammar with this name's own labels filled in.
   *
   * <p>Built from the STATED domain and from labels the reading already resolved, never by splicing
   * the {@code Host} header: a project slug printed here is one this edge knows, and the apex
   * sentence names no caller input at all.
   *
   * <p>Static so the three sentences can be asserted without a boot — see {@code
   * EdgeRouterNamesTest}.
   */
  static String doorBody(HostEnvironments.Route named, String domain) {
    return switch (named.reading()) {
      case PROJECT_DOOR ->
          "This name is the `"
              + named.project()
              + "` project's door and serves nothing. Every application of it is on its own name, `"
              + "<app>."
              + named.project()
              + "."
              + domain
              + "`.\n";
      case PROJECT_LANDING_DOOR ->
          "This name is the `"
              + named.project()
              + "` project's door, and its front page is a deployment that runs once per"
              + " environment — so it is served at `"
              + named.environment()
              + "."
              + named.project()
              + "."
              + domain
              + "`, where `GET /` here sends a browser.\n";
      case ENVIRONMENT_DOOR ->
          "This name is the `"
              + named.environment()
              + "` environment of the `"
              + named.project()
              + "` project and serves nothing. Every application in it is on its own name, `<app>."
              + named.environment()
              + "."
              + named.project()
              + "."
              + domain
              + "`.\n";
      default ->
          "This name serves nothing. Every application is inside a project, on `<app>.<project>."
              + domain
              + "` or `<app>.<env>.<project>."
              + domain
              + "`.\n";
    };
  }

  /**
   * What a name whose project label names no project is answered.
   *
   * <p>There is no refusal of its own and no redirect: a missing or mistyped project label is a
   * name with a label wrong, which is an ordinary 404. It is answered only once {@link
   * ProjectSansBootstrap#authoritative()} — before that the same name is a 503, because the
   * projection may simply not have read that project yet.
   */
  private void unknownProject(HttpServerRequest request, HostEnvironments.Route named) {
    LOG.infof("no such project: %s %s on %s", request.method(), request.path(), authority(request));
    request
        .response()
        .setStatusCode(404)
        .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
        .end(unknownProjectBody(named, hostEnvironments.domain()));
  }

  /**
   * What a name the grammar cannot describe at all is answered: too many labels, or an environment
   * label the named project does not have.
   */
  private void unreadable(HttpServerRequest request, HostEnvironments.Route named) {
    LOG.infof(
        "a name outside the grammar was dialled: %s %s on %s",
        request.method(), request.path(), authority(request));
    request
        .response()
        .setStatusCode(404)
        .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
        .end(unreadableBody(named, hostEnvironments.domain()));
  }

  /**
   * <b>The project label came off the wire</b>, so it is echoed only when it IS a label — the same
   * laundering {@link #unknownAppBody} does, and for the same reason: this string is written back
   * to a caller who chose it. A {@code Host} of {@code ..example.com} would otherwise produce a
   * sentence about an empty name, and anything smuggled past the header parser would come back
   * verbatim.
   */
  static String unknownProjectBody(HostEnvironments.Route named, String domain) {
    return (named.project() == null ? "That label" : "`" + named.project() + "`")
        + " is not a project on this platform. Every name is read right to left — `<app>[.<env>]."
        + "<project>."
        + domain
        + "` — so the label in front of `"
        + domain
        + "` is a project, and this one is not one.\n";
  }

  /** Why a name with a readable project label is still not a name. */
  static String unreadableBody(HostEnvironments.Route named, String domain) {
    if (named.reading() == HostEnvironments.Reading.RESERVED_LABEL) {
      // `landing` means the project's root, which is `<project>.<domain>` — so the label is never a
      // name of its own, whether or not a deployment published it. One thing, one address.
      return "`"
          + HostEnvironments.LANDING
          + "` is not an application name: it is the label a deployment publishes to serve the `"
          + named.project()
          + "` project's own name, `"
          + named.project()
          + "."
          + domain
          + "`. That is where it is, and it is not also here.\n";
    }
    return named.project() == null
        ? "This name has more labels than the grammar has: a name is `<app>[.<env>].<project>."
            + domain
            + "` and nothing deeper.\n"
        : "The `"
            + named.project()
            + "` project does not have an environment by that name. Its applications are on"
            + " `<app>.<env>."
            + named.project()
            + "."
            + domain
            + "`.\n";
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
        hostEnvironments,
        projects.projects(),
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
        // Nobody vouched for this caller, so no identity may arrive upstream — proxy() strips the
        // reserved namespace on every path now, so this needs no strip of its own.
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
   * the page is at {@code https://idp.<env>.<project>.<domain>/idp/login}. The canonical origin
   * cannot follow it: it is also the origin a name that names no project falls back to — see {@link
   * EnvironmentAuthority} — so it stays the door and is only the fallback here.
   *
   * <p>It is null, and the canonical origin answers, when the name this request arrived on is
   * inside no project: there is then no application address to compose at all.
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
    // The reserved X-Qits-* namespace is stripped on EVERY path out of this process, and only a
    // validated session's own identity is written back — both halves in one call, see
    // EdgeHeaders.applyIdentity. A null session (a machine credential, or a read the deployment
    // opened) strips and asserts nothing: its identity is in its own token, and a client-supplied
    // X-Qits-User must never reach an upstream that trusts the prefix unconditionally. This used to
    // strip only on the session branch, so a machine or anonymous request could forge an identity a
    // forward-auth service behind the edge believed — the strip is the whole basis of that trust
    // and
    // cannot be conditional on there being a session to replace it with. On the ordinary path the
    // proxy copies these headers upstream; on an upgrade it forwards this same map, so one call
    // covers a path the interceptor chain never sees.
    EdgeHeaders.applyIdentity(request.headers(), session);
    if (session == null) {
      // A parent-domain browser session reaches every sibling name by browser design. This request
      // is not using one — it is a machine's, or a read the deployment opened — so the cookie is
      // removed before the service sees it; unrelated application cookies remain intact.
      EdgeHeaders.stripCookie(request.headers(), sessions.cookieName());
    }
    if (isWebSocketUpgrade(request)) {
      // An upgrade never reaches the interceptor chain — it is the edge's own path, see
      // EdgeWebSocketUpgrade — so the forwarded headers are written onto the inbound request,
      // whose header map that path forwards. See EdgeHeaders.applyForwarded.
      EdgeHeaders.applyForwarded(request.headers(), request);
      Upstream upstream = upstreamOf(request, target);
      webSocketUpgrade.handle(request, upstream, originRequestOptions(upstream));
      return;
    }
    if (target.host() != null) {
      endpointProxy(upstreamOf(request, target)).handle(request);
      return;
    }
    // Only a service target reaches this method, and a service with no published host is a
    // CONFIGURED vhost: the whole name is one service, exactly as it was before the projection
    // carried any.
    appProxies.get(target.route().app() + "." + target.environment()).handle(request);
  }

  /**
   * The upstream this request is for, one answer for both transports: the owning endpoint's when a
   * route travels here, the published host's otherwise, and the configured app grid's for a vhost
   * the projection has not claimed.
   */
  private Upstream upstreamOf(HttpServerRequest request, Target target) {
    EdgeRoutes.ServiceHost host = target.host();
    if (host != null) {
      EdgeEndpoint endpoint = routes.resolve(target.environment(), request.path());
      if (endpoint != null && travels(target, endpoint, host)) {
        return endpoint.upstream();
      }
      return host.upstream();
    }
    return appUpstream(config.apps().get(target.route().app()), target.environment());
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
    return endpointProxies.computeIfAbsent(upstream, this::reverseProxy);
  }

  /**
   * Package-visible for the local navigation route: it must use the same host resolution as
   * proxying.
   */
  String environment(HttpServerRequest request) {
    return hostEnvironments.route(authority(request), projects.projects()).environment();
  }

  private void unknownApp(HttpServerRequest request, HostEnvironments.Route route) {
    request
        .response()
        .setStatusCode(404)
        .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
        .end(unknownAppBody(route, hostEnvironments.apps()));
  }

  /**
   * What an app-shaped name nobody claims is told, with the label echoed only when it IS one.
   *
   * <p><b>The label came off the wire.</b> {@link HostEnvironments} carries it as far as here so
   * the answer can name it, and it is the first label of a {@code Host} header — attacker input,
   * whatever this process does with it. The routing readings do not care, because a label nothing
   * matches is unroutable either way; the ANSWER does, because it is written back to the caller.
   * {@code Host: .prod.example.com} used to produce a sentence about an empty name, and anything
   * else a client could smuggle through the header parser came back verbatim. So it is laundered
   * here on the same rule {@code shortForm}'s label already obeys — echoed when {@link
   * HostEnvironments#isLabel}, described generically otherwise — and the media type stays {@code
   * text/plain}, which is the other half of why this is safe to read in a browser.
   */
  static String unknownAppBody(HostEnvironments.Route route, java.util.Set<String> apps) {
    String label = route.unknownApp();
    return (label != null && HostEnvironments.isLabel(label)
            ? "`" + label + "`"
            : "That first label")
        + " is not an application this edge routes. Configured: "
        + apps
        + (route.project() == null
            ? " — the environment `"
                + route.environment()
                + "` was read from the name and is fine.\n"
            : " — the environment `"
                + route.environment()
                + "` and the project `"
                + route.project()
                + "` were read from the name and are fine.\n");
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
