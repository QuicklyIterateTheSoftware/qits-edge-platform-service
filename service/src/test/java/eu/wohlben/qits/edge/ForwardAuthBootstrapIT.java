package eu.wohlben.qits.edge;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.edge.testdb.EmbeddedPg;
import eu.wohlben.qits.servicemock.MockService;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.vertx.core.http.HttpMethod;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;

/**
 * The whole service as it is <b>packaged</b>, with the browser gate ON, in front of a service that
 * records what arrived — the one posture this repository's suite has never had, and the only one in
 * which the platform's central trust claim is a fact rather than a sentence.
 *
 * <p>That claim is the reason this file exists. Half the fleet's CLAUDE.md files say some version
 * of "the edge asserts {@code X-Qits-User} / {@code X-Qits-User-Id} / {@code X-Qits-Roles} and
 * every service behind it believes them unconditionally", and each of those services tests the
 * BELIEVING half against headers its own suite wrote. Nobody tests the ASSERTING half except this
 * repository, and until now it did so with {@code @QuarkusTest} alone: {@code EdgeSessionGateTest}
 * proves the strip-then-inject against in-process stub upstreams inside the same JVM as the router.
 * What only a launched process has is this:
 *
 * <ul>
 *   <li><b>The gate is a boot-time decision, and this is the boot that makes it.</b> {@code
 *       qits.edge.sessions.enabled} ships <b>false</b> — deliberately, so the gate lands inert and
 *       is flipped as a release step of its own — and {@code EdgeSessions#requireItsOwnCredential}
 *       refuses to start a process that turns it on with no idp client to introspect with. A
 *       {@code @QuarkusTest} flips a flag in an application it is already inside; here the flag,
 *       the credential and the startup check are handed to an artifact that was already built, as
 *       {@code -D} arguments, exactly as a deployment hands them over.
 *   <li><b>The strip is asserted from the OTHER side of the wire.</b> The upstream is a {@link
 *       MockService}, a separate process' worth of separation from the router: what it recorded is
 *       what a real service would have believed. An in-process stub and a real proxied socket are
 *       the same assertion right up until {@code vertx-http-proxy} copies a header map, and copying
 *       the header map is the whole subject.
 *   <li><b>The refusals are the real ones.</b> No dev-user, no synthetic identity, no test security
 *       — the launched process authenticates a cookie against idp or it refuses, and the two shapes
 *       of refusal (the login redirect a browser can follow, the {@code WWW-Authenticate} challenge
 *       docker acts on) are what a caller outside the platform actually receives.
 *   <li><b>Health and the door answer before any of it.</b> Both are properties of route ORDER in a
 *       booted Vert.x router — {@code EdgeRouter.ROUTE_ORDER} and the explicit {@code /q} skip —
 *       and a packaged process is where an ordering mistake would show.
 * </ul>
 *
 * <p><b>What is under test, precisely.</b> The vhost these stories drive is a <i>service</i> vhost:
 * a name of the shape {@code <app>.<env>.<domain>} that reaches a service, which is the kind of
 * name {@code EdgeRouter.serviceGate} gates and the kind a person types. It is configured through
 * {@code qits.edge.apps}, which is the one route fixture that survives into a launched process —
 * this repository's own suite reaches the same vhosts the same way, through {@code StubGateways}'
 * {@code qits.edge.apps.<app>.hosts.<env>} overrides. The deployment projection is the OTHER way a
 * name reaches a service, and it is deliberately absent here: it is rebuilt from qits-events at
 * boot, and these stories are about what the edge does with a request rather than about how it
 * learnt the route. {@code qits.edge.projection.catchup.required=false} is the escape hatch the
 * code carries for exactly that, and it is the same line {@code
 * src/test/resources/application.properties} sets.
 *
 * <p><b>One thing this posture found, and it belongs in a comment rather than in a silence.</b>
 * {@link EdgeSessions}' class javadoc says "a CONFIGURED application vhost with no published host
 * is untouched by every line here — it fronts a service no browser talks to, and its gate is {@code
 * AuthConfig}'s". The CODE says otherwise, and the stories below rely on the code: {@code
 * EdgeRouter.handle} routes any target for which {@code Target.service()} holds — which {@code
 * route.toApp()} alone satisfies — into {@code serviceGate}, so a configured vhost carrying a
 * session cookie is introspected, stripped and stamped exactly like a published one. Nothing here
 * argues that is wrong; a name a person types is a name a person types. But the two sentences do
 * not agree, and this is the file that would notice.
 *
 * <p><b>What is NOT under test, and must not be read into these reports.</b> The strip proven below
 * is the one on the path where the edge <i>asserts an identity of its own</i> — a validated browser
 * session. A request that carries a MACHINE credential takes {@code EdgeRouter.machine}'s path and
 * is proxied with no identity asserted for it (a machine's identity is in its token), and a request
 * a deployment opened through {@code qits.edge.auth.anonymous-read-apps} is proxied with none
 * either. Neither of those paths runs {@code EdgeHeaders.applyIdentity}, so a client-supplied
 * {@code X-Qits-*} header travels on them — which {@code
 * EdgeSessionGateTest.anApplicationVhostIsUnchangedByTheBrowserGate} already pins as the intended
 * behaviour for a registry vhost no browser talks to. Anybody reading "a forged identity never
 * crosses the edge" as unconditional is reading more than this file proves and more than the code
 * does.
 *
 * <p><b>The far side is two mocks, and both are recorders.</b> One impersonates
 * <b>qits-projects</b> — one upstream application behind one vhost — so every header claim is
 * assertable on the receiving end rather than on a status code. The other impersonates
 * <b>qits-platform-idp</b>, which is where this service's browser half gets its answers: the edge
 * holds no session store and decides nothing about a cookie on its own, so idp's recordings are
 * what prove it ASKED, with its own client credential, rather than believed. What the idp mock
 * cannot stand in for is telling one cookie from another — a canned stub answers every token the
 * same way — and it does not need to: {@code EdgeSessionGateTest}'s stub idp carries three
 * sessions, a revocation and an outage, and pins that half against the suite's own JVM. What is
 * proven here and nowhere else is that a launched artifact asks at all, over a real socket, with
 * the credential a deployment gave it.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted
 * under {@code service/target/userstories/} with the interactions drawn as a sequence diagram. Both
 * stories are browserless (an {@code Interactions} parameter and no {@code Flow}), so the
 * framework's transitive Playwright never launches anything — which is what lets this run in a step
 * container with no browser in it.
 *
 * <p><b>The requests are driven by {@link EdgeClient} rather than by rest-assured</b>, for the
 * reason written on that class: this service routes on {@code Host}, and rest-assured derives that
 * header from the URL it was given. Vert.x separates "where the socket goes" from "what the request
 * says", which is the one thing an edge test cannot do without. rest-assured is used for the health
 * probe alone, where the name genuinely does not matter.
 *
 * <p><b>This IT is named on the command line rather than opted in from the pom</b> ({@code
 * .config/qits/ci-event-userflows.yml} passes {@code -DskipITs=false
 * "-Dit.test=ForwardAuthBootstrapIT"}). The README documents {@code ./mvnw verify} as "unit tests +
 * the end-to-end proxy suite (no docker, no network)" from a clone of this repository alone, and
 * that build is already the longest of its kind here — the surefire half forks TWO JVMs, because
 * {@code EdgeSessionGateTest} needs a Quarkus of its own, and spawns a real PostgreSQL. Opting
 * {@code verify} into a launched front door plus a SECOND embedded postgres (failsafe forks its own
 * JVM, so the suite's cannot be reused) would change that build for everybody to run what CI runs
 * anyway. {@code skipITs} therefore stays true in {@code pom.xml} and keeps meaning "run
 * everything" for the {@code native} profile that flips it — where this IT earns its place twice
 * over, since a native edge is exactly where a lost reflection registration or a client built in a
 * class initialiser hides.
 */
@QuarkusIntegrationTest
@TestProfile(ForwardAuthBootstrapIT.PackagedWithTheBrowserGateOn.class)
public class ForwardAuthBootstrapIT {

  static final String CATEGORY = "edge";

  static final String IDENTITY_SLUG =
      "a-forged-identity-never-reaches-the-service-and-the-one-idp-vouched-for-does";

  static final String DOOR_SLUG = "the-door-serves-nothing-and-a-health-probe-is-never-gated";

  // --- the two services the mocks impersonate ---------------------------------------------------

  /**
   * The upstream application behind the vhost under test — also the {@link
   * MockService#ensureStarted} key. qits-projects is the service the environment door itself points
   * a visitor at, so it is the one whose name a person types first and the natural stand-in for "a
   * service that reads the identity headers".
   */
  static final String UPSTREAM = "qits-projects";

  /**
   * The identity provider — also its {@link MockService#ensureStarted} key. The edge consumes this
   * service for browser sessions and for nothing else in these stories: it holds no OIDC tenant of
   * its own, validates tokens offline against a key set it fetches from here, and introspects
   * cookies here over a socket.
   */
  static final String IDP = "qits-platform-idp";

  // --- the names ---------------------------------------------------------------------------------

  /** The app label, which is the first label of the vhost and the key of its {@code apps} entry. */
  static final String APP = "projects";

  /**
   * The tier under test. Two environments and a default that is the OTHER one, which is the
   * platform's own shape and the shape {@code StubGateways} gives the suite: it keeps {@code dev}'s
   * names carrying their label, so every string below is the long, unambiguous spelling.
   */
  static final String ENVIRONMENT = "dev";

  /** The service vhost: {@code <app>.<env>.<domain>}, the name a person types for one service. */
  static final String SERVICE_HOST = APP + "." + ENVIRONMENT + ".example.com";

  /** The environment's own name, which is the door — it routes nothing and serves nothing. */
  static final String DOOR_HOST = ENVIRONMENT + ".example.com";

  /** An app-shaped name no configuration and no deployment claims. It is a 404, not a fallback. */
  static final String UNCLAIMED_HOST = "nosuchservice." + ENVIRONMENT + ".example.com";

  /**
   * The environment door of the DEFAULT environment, which is the apex — and the origin every
   * default-environment name is derived from. It is the login page's fallback while no deployment
   * has published a host for whoever owns {@code /idp/login}, which is the case here.
   */
  static final String CANONICAL_ORIGIN = "https://example.com";

  // --- the identity idp vouches for --------------------------------------------------------------

  /** The cookie a browser sends. Opaque to this process: 256 random bits, stored hashed at idp. */
  static final String SESSION = "a-live-browser-session";

  /** A second cookie value, so a story that must not disturb the first's cache uses its own key. */
  static final String OTHER_SESSION = "another-live-browser-session";

  static final String SESSION_USER = "operator";

  static final String SESSION_USER_ID = "b7e4a1c2-0000-4000-8000-00000000beef";

  /** The two rows the platform's register token grants the first account. */
  static final List<String> SESSION_ROLES = List.of("qits-platform:admin", "qits:admin");

  /** Those roles as one header value — comma-separated, which is what an upstream parses. */
  static final String SESSION_ROLES_HEADER = "qits-platform:admin,qits:admin";

  // --- what a forger sends -----------------------------------------------------------------------

  /** The name a forger would like an upstream to write into its audit column. */
  static final String FORGED_USER = "admin";

  static final String FORGED_USER_ID = "00000000-0000-0000-0000-000000000000";

  static final String FORGED_ROLES = "qits:root,qits-platform:admin";

  /**
   * A reserved header nobody has invented yet, and the sharpest assertion in this file. The strip
   * rule is the PREFIX rather than a list of three names — an enumerated list's failure mode is
   * adding a trusted header and forgetting to extend it, which is silent, additive and untestable
   * by any test that only names today's headers. This one exists so the rule is tested rather than
   * the list.
   */
  static final String INVENTED_HEADER = "X-Qits-Something-Invented-Later";

  // --- the edge's own idp client
  // ------------------------------------------------------------------

  /**
   * The credential the edge introspects with. On the platform the bootstrap seeds {@code
   * {env}-qits-edge} and injects the pair; these two spellings are a contract with
   * cli/qits-bootstrap, and {@link SessionsConfig} deliberately gives them no default — a gate with
   * no credential of its own could never open, so the process refuses to START rather than refusing
   * every browser for a reason only a stack trace holds.
   */
  static final String EDGE_CLIENT_ID = ENVIRONMENT + "-qits-edge";

  static final String EDGE_CLIENT_SECRET = "an-edge-secret";

  // --- paths -------------------------------------------------------------------------------------

  /** The edge's own surface, never proxied, answered whatever the Host name says. */
  static final String READY = "/q/health/ready";

  static final String LIVE = "/q/health/live";

  /** The read a logged-in person's SPA makes. Stubbed, and it is the request that must arrive. */
  static final String IDENTITY_PATH = "/projects/api/me";

  /** A second read behind the same cookie, which is what makes the session cache observable. */
  static final String SECOND_PATH = "/projects/api/workspaces";

  /** A machine-shaped read with no credential. Unstubbed on purpose: it must never arrive. */
  static final String REFUSED_MACHINE_PATH = "/projects/api/things";

  /** A navigation with no credential. Unstubbed for the same reason. */
  static final String REFUSED_NAVIGATION_PATH = "/projects/runs/7";

  /** What a person typing the door's name asks for, and the one path a door has an answer to. */
  static final String DOOR_PATH = "/";

  /** A real service route, asked for on the door's name — which owns no route at all. */
  static final String DOOR_SERVICE_PATH = "/projects/api/runs";

  /** idp's introspection endpoint, as {@link Idp} derives it from the one key that names idp. */
  static final String INTROSPECT = "/idp/api/sessions/introspect";

  /**
   * Refuses a connection at once rather than hanging — the offline spelling this repository's own
   * test configuration already uses for an address that must never be dialled.
   */
  static final String CLOSED_PORT = "http://127.0.0.1:1";

  /**
   * Marks the stubs as registered, for the same reason {@code MockIdp} parks its keypair: a test
   * profile is instantiated in more than one classloader and a static field written by one copy is
   * not the field another reads, while the JVM has exactly one property table. {@link
   * MockService#ensureStarted} already makes each SERVER singular; the stubs live on the owning
   * instance, so this is what keeps the second copy from trying (and failing) to re-register them
   * on an attached handle.
   */
  private static final String STUBBED_PROPERTY = "qits.edge.it.mocks-stubbed";

  /**
   * Hands the launched artifact its configuration the way a deployment does.
   *
   * <p>Every key here is a <b>runtime</b> key. A packaged process takes its configuration as {@code
   * -D} arguments on an artifact that was already built, so a build-time key would be silently
   * ignored and this would prove something other than what it says. Everything that makes this
   * service what it is stays exactly as it ships: the 1088M body ceiling, the one-hour idle timeout
   * that keeps terminals and SSE channels alive, the {@code /q} non-application root, the OTel
   * logging quartet, ACME off, and — above all — the {@code X-Qits-*} strip rule itself, which is
   * code and not configuration.
   *
   * <p>Four groups, and only two of them are inputs:
   *
   * <ul>
   *   <li><b>The two database triples</b> — the platform's generic resource contract, which
   *       qits-platform-deployments injects and the shipped datasource expressions read with no
   *       defaults on purpose. {@code edge} is the routing projection; {@code eventstream} is
   *       qits-eventstream's claim ledger, and it is needed even though the bus is dark below,
   *       because dark does not mean absent: Quarkus opens the connection and runs Flyway at boot
   *       regardless. Both are databases of this IT's own on the same embedded postgres the
   *       surefire suite spawns, so the launched process and any suite can never mean the same
   *       schema. Their urls travel through system properties rather than static fields, for the
   *       classloader reason above.
   *   <li><b>The environment list and the one application vhost</b> — the deployment's own inputs,
   *       stated exactly as {@code QITS_EDGE_ENVIRONMENTS} and {@code
   *       QITS_EDGE_APPS_<APP>_HOST_PATTERN} state them. The {@code host-pattern} is a name nothing
   *       resolves and the {@code hosts.dev} override is the mock's real address: a request that
   *       reached the pattern would be a resolution bug rather than a test that happened to pass.
   *   <li><b>The browser gate</b> — the flag this service ships DARK, plus the credential and the
   *       origins that make it legal to turn on. This is the whole point of the run: with the flag
   *       false a request takes the path it took before the gate existed, and nothing below would
   *       be under test.
   *   <li><b>Four neutralisations.</b> The event bus is dark and its address is a closed port, so a
   *       projection this run does not use cannot spend it dialling {@code qits-events}, a name
   *       that resolves on qits-net and nowhere else. The startup catch-up barrier is off, which is
   *       the code's own escape hatch for a deliberately offline setup and the same line the
   *       suite's properties file carries — with it on, every request answers 503 while a bus that
   *       is not there is waited for. And OTel is dark, like {@code %dev}/{@code %test}: the
   *       shipped exporter points at {@code http://qits-observability:8080}, another qits-net-only
   *       name.
   * </ul>
   */
  public static class PackagedWithTheBrowserGateOn implements QuarkusTestProfile {

    /** Where each url is parked for whichever copy of this class is asked second. */
    private static final String EDGE_URL_PROPERTY = "qits.test.userflow-it.edge-db-url";

    private static final String EVENTSTREAM_URL_PROPERTY =
        "qits.test.userflow-it.eventstream-db-url";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockService upstream = mocksStartedAndStubbed();
      MockService idp = MockService.attach(IDP);

      Map<String, String> config = new LinkedHashMap<>();

      config.put("QITS_RESOURCE_EDGE_URL", databaseUrl(EDGE_URL_PROPERTY, "edge_userflows_it"));
      config.put("QITS_RESOURCE_EDGE_USERNAME", EmbeddedPg.USER);
      config.put("QITS_RESOURCE_EDGE_PASSWORD", EmbeddedPg.PASSWORD);
      config.put(
          "QITS_RESOURCE_EVENTSTREAM_URL",
          databaseUrl(EVENTSTREAM_URL_PROPERTY, "edge_eventstream_userflows_it"));
      config.put("QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER);
      config.put("QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD);

      config.put("qits.edge.environments", "prod," + ENVIRONMENT);
      config.put("qits.edge.default-environment", "prod");
      config.put("qits.edge.apps." + APP + ".host-pattern", "{env}-qits-projects");
      config.put("qits.edge.apps." + APP + ".hosts." + ENVIRONMENT, address(upstream));

      config.put("qits.edge.sessions.enabled", "true");
      config.put("qits.edge.sessions.canonical-origin", CANONICAL_ORIGIN);
      // The apex is the canonical origin and must be covered or startup fails; the wildcard is one
      // line that follows the deployment's application list instead of copying it.
      config.put(
          "qits.edge.sessions.browser-hosts", "example.com," + DOOR_HOST + ",*." + DOOR_HOST);
      config.put("qits.edge.sessions.client-id", EDGE_CLIENT_ID);
      config.put("qits.edge.sessions.client-secret", EDGE_CLIENT_SECRET);

      // ONE key names the receiver; /jwks, /token and /api/sessions/introspect are derived from it
      // in Idp.java, so a rename on either side fails here rather than in production.
      config.put("qits.idp.url", idp.baseUrl() + "/idp");

      config.put("qits.eventstream.enabled", "false");
      config.put("qits.events.url", CLOSED_PORT);
      config.put("qits.edge.projection.catchup.required", "false");
      config.put("quarkus.otel.sdk.disabled", "true");

      return Map.copyOf(config);
    }

    private static synchronized String databaseUrl(String property, String database) {
      String recorded = System.getProperty(property);
      if (recorded != null) {
        return recorded;
      }
      // localhost resolves for the launched process too — it is a child of this JVM on this host.
      String url = EmbeddedPg.url(database);
      System.setProperty(property, url);
      return url;
    }
  }

  /**
   * Start both mocks once per JVM and stub the routes these stories reach.
   *
   * <p>The upstream is stubbed only for the paths that must ARRIVE. Every path that must not is
   * left unstubbed on purpose: an unstubbed route is still recorded, so "the request never reached
   * the service" is asserted on the recordings rather than inferred from a status the edge chose,
   * and a strip that silently started forwarding refused requests would fail here rather than pass
   * quietly.
   *
   * <p>idp gets one route, and it answers the same session for any cookie. That is the honest limit
   * of a canned stub and it is stated in the class javadoc: what is under test here is what the
   * edge does WITH idp's answer and that it asked for one, not idp's ability to tell a live cookie
   * from a revoked one. The answer's shape is qits-platform-idp's own — {@code userId}, {@code
   * username}, a {@code roles} array and an ISO-8601 {@code expiresAt} — because {@code
   * EdgeSessions.read} refuses an answer it cannot read, and a stub that got the shape wrong would
   * prove a refusal while looking like a proof of admission.
   */
  static synchronized MockService mocksStartedAndStubbed() {
    if (System.getProperty(STUBBED_PROPERTY) != null) {
      return MockService.attach(UPSTREAM);
    }
    MockService upstream = MockService.ensureStarted(UPSTREAM);
    upstream.stub("GET", IDENTITY_PATH, Map.of("answered", "the service read its caller"));
    upstream.stub("GET", SECOND_PATH, Map.of("answered", "the service read its caller again"));

    MockService idp = MockService.ensureStarted(IDP);
    idp.stub(
        "POST",
        INTROSPECT,
        Map.of(
            "userId", SESSION_USER_ID,
            "username", SESSION_USER,
            "roles", SESSION_ROLES,
            // Twelve hours out: a session's own expiry is honoured whatever any cache believes, so
            // one that had already run out would make every admission below a refusal.
            "expiresAt", java.time.Instant.now().plusSeconds(43_200).toString()));

    System.setProperty(STUBBED_PROPERTY, "true");
    return upstream;
  }

  private static EdgeClient client;

  /**
   * Built on first use, against the port the launched artifact was given. Not a field initialiser:
   * {@code RestAssured.port} is set by the integration-test extension when the process is up, and a
   * static initialiser would read it before that.
   */
  private static EdgeClient client() {
    if (client == null) {
      client = new EdgeClient(RestAssured.port);
    }
    return client;
  }

  @AfterAll
  static void closeClient() {
    if (client != null) {
      client.close();
      client = null;
    }
  }

  @UserStory(
      value = "A forged identity never reaches the service, and the one idp vouched for does",
      category = "edge")
  @UserStoryDescription(
      """
      Every service on this platform believes three headers without checking them: `X-Qits-User`,
      `X-Qits-User-Id` and `X-Qits-Roles` say who a caller is, and there is no second opinion
      anywhere behind this process. That is a sound arrangement and a cheap one, and it rests
      entirely on one property of the front door — that the door writes those headers and a client
      cannot.

      So the story is told from the far side. A stranger asks a service host for a page and stamps
      the three headers on the request herself, hoping the edge passes them along. She is refused
      twice over, in the two shapes a caller can act on: a browser navigating is sent to the login
      page with the name and path it was trying to reach, and a machine client with no session to
      offer gets the `WWW-Authenticate` challenge `docker` reads — Bearer first, because docker
      acts on the first scheme it recognises, and Basic behind it, because maven and npm will only
      spend credentials against a scheme they implement. Neither request reaches the service at
      all, which is the part only the service's own recording can say.

      Then she logs in, and sends exactly the same forged headers with the cookie. This time the
      request is served — and the service is told she is the person idp vouched for, not the
      administrator she claimed to be. Her three forgeries are gone, and so is a fourth header
      nobody has invented yet: the rule is the reserved prefix rather than a list of names, because
      a list is a thing somebody extends a day late.

      What makes the identity real is that the edge does not decide it. The cookie is opaque — 256
      random bits, stored hashed at idp — so this process asks, over a socket, presenting its own
      client credential, because an introspection endpoint anyone could call would be an oracle
      about everybody's session. And it asks ONCE for a browser that keeps browsing: every image
      and every poll carries the same cookie, so a gate that asked every time would put idp on the
      path of every request a logged-in person makes.
      """)
  void aForgedIdentityIsRefusedAndAValidatedOneIsAssertedInItsPlace(Interactions story) {
    MockService upstream = MockService.attach(UPSTREAM);
    MockService idp = MockService.attach(IDP);

    story.note(
        "qits-platform-edge starts as the platform's only published port, with the browser gate on,"
            + " in front of one service and beside the identity provider it introspects at");
    given().get(READY).then().statusCode(200).body("status", equalTo("UP"));

    // --- (1) the stranger, in a browser. A navigation is the one request that can render a login
    // page, so it is the one refusal that is a redirect. The whole path and query come back as
    // return_path so the page can put her where she was going; the return host is checked against
    // the browser-host allow-list rather than reflected, and the origin is the door's, because no
    // deployment here has published a host for whoever owns /idp/login.
    EdgeClient.Answer navigation =
        client()
            .send(
                HttpMethod.GET,
                SERVICE_HOST,
                REFUSED_NAVIGATION_PATH,
                null,
                forged(Map.of("Sec-Fetch-Mode", "navigate", "Accept", "text/html")));
    assertEquals(302, navigation.status());
    assertEquals(
        CANONICAL_ORIGIN
            + "/idp/login?return_host="
            + SERVICE_HOST
            + "&return_path=%2Fprojects%2Fruns%2F7",
        navigation.headers().get("location"),
        "a refused navigation must carry the name and path it was trying to reach");
    assertEquals(
        "no-store",
        navigation.headers().get("cache-control"),
        "a cached redirect would keep sending a logged-in browser back to the login page");
    assertEquals(
        0,
        requestsTo(upstream, REFUSED_NAVIGATION_PATH),
        "a refused navigation must not reach the service, forged headers or not");
    story
        .happened(
            "a stranger's browser",
            "qits-platform-edge",
            "GET "
                + REFUSED_NAVIGATION_PATH
                + " on "
                + SERVICE_HOST
                + " (forged X-Qits-*, no"
                + " session) -> 302 to the login page")
        .as("a-forged-navigation-is-sent-to-the-login-page");

    // --- (2) the same stranger as a machine. No Sec-Fetch-Mode and no Accept for HTML, so this is
    // not a navigation and a 302 would be useless to it. The two challenges and their ORDER are the
    // contract: docker walks them and acts on the first scheme it knows, and a Basic challenge in
    // front would stop the token flow being used at all.
    EdgeClient.Answer challenged =
        client()
            .send(
                HttpMethod.GET,
                SERVICE_HOST,
                REFUSED_MACHINE_PATH,
                null,
                forged(Map.of("Accept", "application/json")));
    assertEquals(401, challenged.status());
    List<String> challenges = challenged.headerValues("WWW-Authenticate");
    assertEquals(2, challenges.size(), "one door, described to two kinds of client");
    assertEquals(
        "Bearer realm=\"http://"
            + SERVICE_HOST
            + EdgeAuth.TOKEN_PATH
            + "\",service=\""
            + SERVICE_HOST
            + "\"",
        challenges.get(0),
        "docker acts on the first scheme it recognises, and it is told where to buy a token");
    assertEquals("Basic realm=\"" + SERVICE_HOST + "\"", challenges.get(1));
    assertTrue(
        challenged.body().contains("UNAUTHORIZED"),
        "the refusal is the Distribution spec's envelope, never an HTML page: "
            + challenged.body());
    assertEquals(
        0,
        requestsTo(upstream, REFUSED_MACHINE_PATH),
        "a challenged machine request must not reach the service either");
    story
        .happened(
            "a machine client with no credential",
            "qits-platform-edge",
            "GET "
                + REFUSED_MACHINE_PATH
                + " on "
                + SERVICE_HOST
                + " -> 401, Bearer challenge then"
                + " Basic")
        .as("a-machine-client-is-challenged-not-redirected");

    // --- (3) the session. The same forged headers, now behind a cookie idp vouches for.
    int askedBefore = introspections(idp);

    EdgeClient.Answer served =
        client()
            .send(HttpMethod.GET, SERVICE_HOST, IDENTITY_PATH, null, forgedWithSession(SESSION));
    assertEquals(200, served.status());
    story
        .happened(
            "a logged-in person",
            "qits-platform-edge",
            "GET "
                + IDENTITY_PATH
                + " on "
                + SERVICE_HOST
                + " (a session cookie AND forged"
                + " X-Qits-* headers)")
        .as("a-session-arrives-carrying-forgeries");
    story
        .happened(
            "qits-platform-edge",
            IDP,
            "POST " + INTROSPECT + " (the cookie, under the edge's own client credential)")
        .as("the-cookie-is-introspected-at-idp");

    // The assertion the whole platform's trust model rests on, made on the SERVICE's own recording:
    // what arrived is what a real qits service would have believed.
    MockService.RecordedRequest arrived = onlyRequestTo(upstream, IDENTITY_PATH);
    assertEquals(
        SESSION_USER,
        arrived.headers().get(EdgeHeaders.USER),
        "the service must be told who idp vouched for, never who the caller claimed to be");
    assertEquals(SESSION_USER_ID, arrived.headers().get(EdgeHeaders.USER_ID));
    assertEquals(SESSION_ROLES_HEADER, arrived.headers().get(EdgeHeaders.ROLES));
    assertNull(
        arrived.headers().get(INVENTED_HEADER),
        "the rule is the `X-Qits-` prefix, so a reserved header nobody has thought of yet is"
            + " stripped too — a list of three names would pass this test and fail in a year");
    // The cookie itself travels on, and that is deliberate on a name a browser holds a session for:
    // the service behind it is an ordinary qits service and the browser will make its next request
    // with the cookie anyway. It is the MACHINE vhosts that must remove it.
    String cookie = arrived.headers().get("Cookie");
    assertTrue(
        cookie != null && cookie.contains("qits-session="),
        "a browser's own credential stays with it on the name it is browsing: " + cookie);
    story
        .happened(
            "qits-platform-edge",
            UPSTREAM,
            "GET "
                + IDENTITY_PATH
                + " (X-Qits-User: "
                + SESSION_USER
                + ", every forged X-Qits-*"
                + " dropped first)")
        .as("the-service-is-told-the-vouched-for-identity");

    // The edge asked, and it asked as itself. Without its own credential in that call, idp's
    // introspection endpoint would be an oracle anybody on the network could ask about any cookie.
    MockService.RecordedRequest asked = onlyRequestTo(idp, INTROSPECT);
    assertEquals("POST", asked.method());
    assertEquals(
        basic(EDGE_CLIENT_ID, EDGE_CLIENT_SECRET),
        asked.headers().get("Authorization"),
        "the edge introspects as its own idp client, which is what makes this a privilege");
    assertEquals(
        "application/json",
        asked.headers().get("Content-Type"),
        "the cookie travels in a JSON body, never in a URL a proxy log would keep");

    // --- (4) and it asked once. The second read behind the same cookie is served from the belief
    // the first one bought, which is what keeps idp off the path of every image and every poll a
    // logged-in page makes.
    assertEquals(
        200,
        client()
            .send(HttpMethod.GET, SERVICE_HOST, SECOND_PATH, null, forgedWithSession(SESSION))
            .status());
    assertEquals(
        SESSION_USER,
        onlyRequestTo(upstream, SECOND_PATH).headers().get(EdgeHeaders.USER),
        "the cached belief must assert the same identity the call established");
    assertEquals(
        askedBefore + 1,
        introspections(idp),
        "two requests behind one cookie are one introspection — every request a browser makes"
            + " carries it, so a gate that asked each time would put idp on all of them");
    story
        .happened(
            "a logged-in person",
            "qits-platform-edge",
            "GET " + SECOND_PATH + " with the same cookie -> served, and idp was not asked again")
        .as("a-second-read-is-served-without-asking-idp-again");
  }

  @UserStory(
      value = "The door serves nothing, and a health probe is never gated",
      category = "edge")
  @UserStoryDescription(
      """
      The other half of a guarded front door: what it must keep answering to callers it knows
      nothing about, and what it must refuse to answer to anybody at all.

      An environment's own name is a DOOR, and a door serves nothing. Every service on the platform
      is reached on its own name now, so a route, an API or a wire protocol offered on the
      environment's name would be a second address for something that already has one — a second
      origin, a second cookie scope, a second thing to keep in step. So it routes nothing and it
      gates nothing, because there is nothing behind it to gate: the same name answers 404 to a
      stranger and 404 to a person holding a perfectly good session, and the message it answers
      with is the useful part — it names the shape every service is actually on. Nothing reaches an
      upstream from there, with or without a credential.

      An app-shaped name nobody claims is a different 404, and it matters that it is one at all. A
      name of the form `<app>.<env>.<domain>` was aimed at a service, and services are the names
      this process authenticates — so a mistyped or decommissioned one has to fail here. Falling
      through to something that serves it would hand exactly those requests to the hop that does
      not check them.

      And the one surface that stays open to everyone, on every name: this process's own health.
      An orchestrator carries no session and can never be given one, so a readiness probe that
      redirected to a login page would be a container that never comes up — and this is the
      container that binds the host's only published port, so nothing on the platform would come up
      behind it. It is answered by this process rather than proxied, whatever Host the probe used,
      and it says what a booted edge is ready FOR: a deployment routing projection it has caught up
      with.
      """)
  void theDoorRefusesEverybodyAlikeWhileHealthAnswersEverybody(Interactions story) {
    MockService upstream = MockService.attach(UPSTREAM);

    // --- (1) the door, to a stranger. GET / is the one path a door has an answer to, and here it
    // is the 404: the redirect it would otherwise send needs a deployment to have published a host
    // for qits-projects, and this run has published none.
    EdgeClient.Answer door = client().get(DOOR_HOST, DOOR_PATH);
    assertEquals(404, door.status());
    assertTrue(
        door.body().contains("This name is the environment door and serves nothing"), door.body());
    assertTrue(
        door.body().contains("`<app>." + DOOR_HOST + "`"),
        "the refusal must name the shape every service IS on, or it is a dead end: " + door.body());
    assertEquals(0, requestsTo(upstream, DOOR_PATH), "the door proxies nothing");
    story
        .happened(
            "a visitor",
            "qits-platform-edge",
            "GET " + DOOR_PATH + " on " + DOOR_HOST + " -> 404 naming `<app>." + DOOR_HOST + "`")
        .as("the-door-serves-nothing");

    // --- (2) the door, to somebody logged in. The same answer, which is the point: the door does
    // not gate, so a session cannot open it and its absence cannot close it. A service route asked
    // for here is 404 too — that route exists on its OWNER's name and nowhere else.
    EdgeClient.Answer doorWithSession =
        client().send(HttpMethod.GET, DOOR_HOST, DOOR_SERVICE_PATH, null, session(OTHER_SESSION));
    assertEquals(404, doorWithSession.status());
    assertEquals(
        0,
        requestsTo(upstream, DOOR_SERVICE_PATH),
        "a service's route does not travel to the environment's own name, session or no session");
    story
        .happened(
            "a logged-in person",
            "qits-platform-edge",
            "GET "
                + DOOR_SERVICE_PATH
                + " on "
                + DOOR_HOST
                + " -> 404, the same answer a stranger"
                + " gets")
        .as("the-door-gates-nothing-because-it-serves-nothing");

    // --- (3) an app-shaped name nobody claims. NOT a fall-through: the name was aimed at a
    // service, and no configuration and no deployment holds it. The 404 names the label and says
    // the environment it read was fine, which is the difference between a typo and an outage.
    EdgeClient.Answer unclaimed = client().get(UNCLAIMED_HOST, IDENTITY_PATH);
    assertEquals(404, unclaimed.status());
    assertTrue(
        unclaimed.body().contains("`nosuchservice` is not an application this edge routes"),
        unclaimed.body());
    assertTrue(
        unclaimed.body().contains("the environment `" + ENVIRONMENT + "` was read from the name"),
        unclaimed.body());
    story
        .happened(
            "a mistyped client",
            "qits-platform-edge",
            "GET " + IDENTITY_PATH + " on " + UNCLAIMED_HOST + " -> 404 naming the label")
        .as("an-unclaimed-service-name-is-refused-rather-than-forwarded");

    // --- (4) health, on a gated service host, with no credential of any kind. This is the one
    // request on that name that must not meet the gate, and it is answered by this process rather
    // than proxied: the `/q` prefix is skipped explicitly, before the Host name is even read for
    // routing.
    EdgeClient.Answer ready = client().get(SERVICE_HOST, READY);
    assertEquals(200, ready.status(), "a readiness probe carries no session and never can");
    assertTrue(ready.body().contains("\"status\""), ready.body());
    assertTrue(ready.body().contains("UP"), ready.body());
    assertTrue(
        ready.body().contains("deployment-projection"),
        "readiness says what a booted edge is ready FOR: " + ready.body());
    assertEquals(200, client().get(SERVICE_HOST, LIVE).status());
    assertEquals(
        0,
        requestsTo(upstream, READY),
        "the edge's own surface is the one thing that never leaves the process");
    story
        .happened(
            "an orchestrator",
            "qits-platform-edge",
            "GET " + READY + " on the gated host " + SERVICE_HOST + " (no credential) -> 200 UP")
        .as("health-is-never-gated-and-never-proxied");

    // …and on the door's name too, because a probe does not know or care which vhost it hit.
    assertEquals(200, client().get(DOOR_HOST, READY).status());
    story
        .happened(
            "an orchestrator",
            "qits-platform-edge",
            "GET " + READY + " on " + DOOR_HOST + " -> 200, whatever the Host name says")
        .as("health-answers-whatever-name-was-used");
  }

  // --- helpers
  // ------------------------------------------------------------------------------------

  /** The three forgeries plus the one nobody has invented yet, and whatever else a beat needs. */
  private static Map<String, String> forged(Map<String, String> extra) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(EdgeHeaders.USER, FORGED_USER);
    headers.put(EdgeHeaders.USER_ID, FORGED_USER_ID);
    headers.put(EdgeHeaders.ROLES, FORGED_ROLES);
    headers.put(INVENTED_HEADER, "whatever a future hop might trust");
    headers.putAll(extra);
    return Map.copyOf(headers);
  }

  /** The same forgeries, behind a cookie idp vouches for. */
  private static Map<String, String> forgedWithSession(String value) {
    return forged(session(value));
  }

  /**
   * A browser's {@code Cookie} header. Two pairs, because a browser sends every cookie it holds for
   * the name and the session one is rarely first.
   */
  private static Map<String, String> session(String value) {
    return Map.of("Cookie", "theme=dark; qits-session=" + value);
  }

  /** HTTP Basic, as the edge builds it for its own idp client. */
  private static String basic(String id, String secret) {
    return "Basic "
        + java.util.Base64.getEncoder()
            .encodeToString((id + ":" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** {@code host:port} out of a mock's base URL — which is what {@link Upstream} parses. */
  private static String address(MockService mock) {
    return URI.create(mock.baseUrl()).getAuthority();
  }

  /** How many times a mock answered exactly {@code path} (query strings excluded). */
  private static long requestsTo(MockService mock, String path) {
    return mock.recordedRequests().stream().filter(request -> path.equals(request.path())).count();
  }

  /** How many cookies idp has been asked about — what proves a cache hit made no call. */
  private static int introspections(MockService idp) {
    return (int) requestsTo(idp, INTROSPECT);
  }

  /**
   * The one request that reached {@code path}, or a failure naming how many did. Exactly one, so a
   * retry or a duplicated proxy hop can never make a header assertion read the wrong request.
   */
  private static MockService.RecordedRequest onlyRequestTo(MockService mock, String path) {
    List<MockService.RecordedRequest> matched =
        mock.recordedRequests().stream().filter(request -> path.equals(request.path())).toList();
    assertEquals(1, matched.size(), "exactly one request must have reached " + path);
    return matched.get(0);
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    ReportAssertions.assertComplete(CATEGORY, IDENTITY_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY,
        IDENTITY_SLUG,
        "qits-platform-edge",
        IDP,
        "POST " + INTROSPECT + " (the cookie, under the edge's own client credential)");
    ReportAssertions.assertStepId(
        CATEGORY, IDENTITY_SLUG, "a-forged-navigation-is-sent-to-the-login-page");
    ReportAssertions.assertStepId(
        CATEGORY, IDENTITY_SLUG, "a-machine-client-is-challenged-not-redirected");
    ReportAssertions.assertStepId(CATEGORY, IDENTITY_SLUG, "a-session-arrives-carrying-forgeries");
    ReportAssertions.assertStepId(CATEGORY, IDENTITY_SLUG, "the-cookie-is-introspected-at-idp");
    ReportAssertions.assertStepId(
        CATEGORY, IDENTITY_SLUG, "the-service-is-told-the-vouched-for-identity");
    ReportAssertions.assertStepId(
        CATEGORY, IDENTITY_SLUG, "a-second-read-is-served-without-asking-idp-again");

    ReportAssertions.assertComplete(CATEGORY, DOOR_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, DOOR_SLUG, "the-door-serves-nothing");
    ReportAssertions.assertStepId(
        CATEGORY, DOOR_SLUG, "the-door-gates-nothing-because-it-serves-nothing");
    ReportAssertions.assertStepId(
        CATEGORY, DOOR_SLUG, "an-unclaimed-service-name-is-refused-rather-than-forwarded");
    ReportAssertions.assertStepId(CATEGORY, DOOR_SLUG, "health-is-never-gated-and-never-proxied");
    ReportAssertions.assertStepId(CATEGORY, DOOR_SLUG, "health-answers-whatever-name-was-used");
  }
}
