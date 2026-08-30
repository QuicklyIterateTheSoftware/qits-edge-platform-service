package eu.wohlben.qits.edge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The names, addresses and identities every story in this catalogue shares — spelled once, so a
 * diagram and the assertion that pins it cannot disagree about what a thing is called.
 *
 * <p><b>A name here is a stable literal, never a run stamp.</b> {@link
 * eu.wohlben.qits.userflows.Labels} rewrites only what it can tell was generated — a UUID, a hex
 * run of 32 or more, a bare numeric path segment — so anything else in a label survives into a
 * story's {@code networkHash}. A fixture named after a port or a timestamp would move that hash on
 * every run, and the only symptom is a hash that never settles.
 *
 * <p><b>A vhost is the whole subject of this service</b>, so the names below are the shape a person
 * types: {@code <app>.<env>.<domain>} for one service, {@code <env>.<domain>} for the door. On this
 * process a name is not decoration — it IS the routing decision, and {@code GET /projects/api/me}
 * means one thing on one name, a different service on another and a 404 on a third.
 */
public final class StoryTarget {

  private StoryTarget() {}

  /** How every diagram in this catalogue names the launched process, on both sides of an edge. */
  public static final String SERVICE = "qits-platform-edge";

  /** The category every story in this catalogue emits under. */
  public static final String CATEGORY = "edge";

  // --- the services behind the door --------------------------------------------------------------

  /**
   * The service the environment door itself points a visitor at, and so the one whose name a person
   * types first. It stands in for "an ordinary qits service that reads the identity headers".
   */
  public static final String PROJECTS = "qits-projects";

  /**
   * A SECOND ordinary service, gated exactly like the first — which is what makes "the name is the
   * routing decision" assertable rather than asserted. Both answer the same path; only the vhost
   * differs, and each one's own recording says which of them received the request.
   */
  public static final String DOCS = "qits-docs";

  /**
   * A MACHINE vhost, whose reads a deployment opened — {@code qits.edge.auth.anonymous-read-apps}.
   * No browser talks to it, which is what makes it the name on which a person's cookie must not
   * arrive and on which nothing may forge an identity either.
   */
  public static final String MIRROR = "qits-platform-mirror";

  /**
   * The identity provider. The edge holds no session store and decides nothing about a cookie on
   * its own: it asks here, over a socket, with its own client credential.
   */
  public static final String IDP = "qits-platform-idp";

  // --- the app labels, which are the first label of a vhost and the key of an `apps` entry
  // --------

  public static final String PROJECTS_APP = "projects";

  public static final String DOCS_APP = "docs";

  public static final String MIRROR_APP = "mirror";

  /**
   * An application whose address is a port nothing listens on. It is CONFIGURED and it is ROUTED —
   * which is the whole difference from a name nobody claims — and there is simply nothing there.
   */
  public static final String OFFLINE_APP = "offline";

  // --- the names ---------------------------------------------------------------------------------

  /**
   * The tier under test. Two environments and a default that is the OTHER one, which is the
   * platform's own shape: it keeps {@code dev}'s names carrying their label, so every string below
   * is the long, unambiguous spelling.
   */
  public static final String ENVIRONMENT = "dev";

  public static final String DOMAIN = "example.com";

  /** The environment's own name, which is the door — it routes nothing and serves nothing. */
  public static final String DOOR_HOST = ENVIRONMENT + "." + DOMAIN;

  public static final String PROJECTS_HOST = PROJECTS_APP + "." + DOOR_HOST;

  public static final String DOCS_HOST = DOCS_APP + "." + DOOR_HOST;

  public static final String MIRROR_HOST = MIRROR_APP + "." + DOOR_HOST;

  public static final String OFFLINE_HOST = OFFLINE_APP + "." + DOOR_HOST;

  /** An app-shaped name no configuration and no deployment claims. It is a 404, not a fallback. */
  public static final String UNCLAIMED_HOST = "nosuchservice." + DOOR_HOST;

  /**
   * The environment door of the DEFAULT environment, which is the apex — and the origin every
   * default-environment name is derived from. It is the login page's fallback while no deployment
   * has published a host for whoever owns {@code /idp/login}, which is the case throughout.
   */
  public static final String CANONICAL_ORIGIN = "https://" + DOMAIN;

  // --- the edge's own surface --------------------------------------------------------------------

  /** Never proxied, answered whatever the Host name says. */
  public static final String READY = "/q/health/ready";

  public static final String LIVE = "/q/health/live";

  // --- the paths the stories drive ---------------------------------------------------------------

  /** The read a logged-in person's SPA makes, and the request that must ARRIVE. */
  public static final String IDENTITY_PATH = "/projects/api/me";

  /** A second read behind the same cookie, which is what makes the session cache observable. */
  public static final String SECOND_PATH = "/projects/api/workspaces";

  /** A machine-shaped read with no credential. Unarmed on purpose: it must never arrive. */
  public static final String REFUSED_MACHINE_PATH = "/projects/api/things";

  /**
   * The one path that is armed on BOTH ordinary services, which is what makes the routing claim an
   * assertion: the same request goes to two different processes and each one's own recording says
   * which of them got it. A path is not a route on this service — a NAME is.
   *
   * <p>Its own path rather than a reused one, because a stand-in's recording is cumulative for the
   * whole run: {@code onlyRequestTo} can only mean "exactly one" on a path exactly one story
   * drives.
   */
  public static final String ROUTED_PATH = "/projects/api/version";

  /** The read the outage story takes away and gives back. Its own path, for the same reason. */
  public static final String OUTAGE_PATH = "/projects/api/repositories";

  /** A page a logged-out browser navigates to. Unarmed: it must never arrive. */
  public static final String WALL_NAVIGATION_PATH = "/projects/settings";

  /** A read a script makes with nothing to offer. Unarmed, same reason. */
  public static final String WALL_READ_PATH = "/projects/api/secrets";

  /** A terminal a logged-out browser tries to open. Unarmed for a socket, and never reached. */
  public static final String WALL_TERMINAL_PATH =
      "/projects/api/terminals/9c1d4e70-0000-4000-8000-0000000000bb";

  /** A navigation with no credential. Unarmed for the same reason. */
  public static final String REFUSED_NAVIGATION_PATH = "/projects/runs/7";

  /** What a person typing the door's name asks for, and the one path a door has an answer to. */
  public static final String DOOR_PATH = "/";

  /** A real service route, asked for on the door's name — which owns no route at all. */
  public static final String DOOR_SERVICE_PATH = "/projects/api/runs";

  /** The SPA document itself: the file that names which build of the application a browser runs. */
  public static final String SPA_DOCUMENT = "/";

  /**
   * A content-hashed bundle, in Angular's own output shape — {@code -} then eight uppercase base-36
   * characters, at least one a digit. Its NAME changes with its content, which is the entire
   * justification for keeping it forever.
   */
  public static final String SPA_BUNDLE = "/main-4RS6EA47.js";

  /** An asset whose name does NOT change with its content — the other half of the same default. */
  public static final String SPA_LOGO = "/assets/qits-logo.svg";

  /**
   * A route whose upstream made a caching decision of its own. A header a handler CHOSE is a
   * decision, and the edge does not overrule decisions — only the one value that is known to be
   * nobody's.
   */
  public static final String PRIVATE_READ = "/projects/api/session";

  /** A docker-shaped read on the machine vhost, which a deployment opened to everyone. */
  public static final String MIRROR_READ = "/v2/library/base/manifests/latest";

  /** A second open read on the same name, so two beats can each own exactly one recording. */
  public static final String MIRROR_LAYER = "/v2/library/base/blobs/latest";

  /** A write on the same name. Opening READS is not opening the service. */
  public static final String MIRROR_WRITE = "/v2/library/base/blobs/uploads/";

  /**
   * An interactive terminal's socket. The id is a real UUID because that is what the platform mints
   * per session; {@link eu.wohlben.qits.userflows.Labels} rewrites it to {@code {id}} in a label,
   * so a story's {@code networkHash} does not move with it.
   */
  public static final String TERMINAL_ID = "3f2a6c18-0000-4000-8000-0000000000aa";

  public static final String TERMINAL_PATH = "/projects/api/terminals/" + TERMINAL_ID;

  /** …and the same path with the id already scrubbed, which is what a label carries. */
  public static final String TERMINAL_LABEL_PATH = "/projects/api/terminals/{id}";

  /** A long answer written over time — an SSE channel, a build log, a `docker pull` progress. */
  public static final String STREAM_PATH = "/projects/api/events";

  /** idp's introspection endpoint, as {@link Idp} derives it from the one key that names idp. */
  public static final String INTROSPECT = "/idp/api/sessions/introspect";

  // --- the identity idp vouches for --------------------------------------------------------------

  public static final String SESSION_USER = "operator";

  public static final String SESSION_USER_ID = "b7e4a1c2-0000-4000-8000-00000000beef";

  /** The two rows the platform's register token grants the first account. */
  public static final List<String> SESSION_ROLES = List.of("qits-platform:admin", "qits:admin");

  /** Those roles as one header value — comma-separated, which is what an upstream parses. */
  public static final String SESSION_ROLES_HEADER = "qits-platform:admin,qits:admin";

  /**
   * One cookie value per story, and that is load-bearing rather than tidiness: {@code EdgeSessions}
   * caches a belief against a fingerprint of the COOKIE, so two stories sharing a value would make
   * the second one's introspection edge depend on how long the first one took. A value of its own
   * means every story's traffic to idp is its own.
   */
  public static final String SESSION = "a-live-browser-session";

  public static final String DOOR_SESSION = "another-live-browser-session";

  public static final String ROUTING_SESSION = "a-session-following-two-links";

  public static final String RETURNING_SESSION = "a-returning-browsers-session";

  public static final String OUTAGE_SESSION = "a-session-during-an-outage";

  public static final String TERMINAL_SESSION = "a-session-at-a-terminal";

  public static final String STREAM_SESSION = "a-session-watching-a-log";

  // --- what a forger sends -----------------------------------------------------------------------

  /** The name a forger would like an upstream to write into its audit column. */
  public static final String FORGED_USER = "admin";

  public static final String FORGED_USER_ID = "00000000-0000-0000-0000-000000000000";

  public static final String FORGED_ROLES = "qits:root,qits-platform:admin";

  /**
   * A reserved header nobody has invented yet, and the sharpest assertion in this catalogue. The
   * strip rule is the PREFIX rather than a list of three names — an enumerated list's failure mode
   * is adding a trusted header and forgetting to extend it, which is silent, additive and
   * untestable by any test that only names today's headers. This one exists so the RULE is tested
   * rather than the list.
   */
  public static final String INVENTED_HEADER = "X-Qits-Something-Invented-Later";

  // --- the edge's own idp client
  // ------------------------------------------------------------------

  /**
   * The credential the edge introspects with. On the platform the bootstrap seeds {@code
   * {env}-qits-edge} and injects the pair; these two spellings are a contract with
   * cli/qits-bootstrap, and {@link SessionsConfig} deliberately gives them no default — a gate with
   * no credential of its own could never open, so the process refuses to START rather than refusing
   * every browser for a reason only a stack trace holds.
   */
  public static final String EDGE_CLIENT_ID = ENVIRONMENT + "-qits-edge";

  public static final String EDGE_CLIENT_SECRET = "an-edge-secret";

  /**
   * Refuses a connection at once rather than hanging — the offline spelling this repository's own
   * test configuration already uses for an address that must never be dialled.
   */
  public static final String CLOSED_PORT_URL = "http://127.0.0.1:1";

  /** The same address as {@code host:port}, which is what an application entry takes. */
  public static final String CLOSED_PORT_ADDRESS = "127.0.0.1:1";

  // --- what the stories send
  // ----------------------------------------------------------------------

  /** The three forgeries plus the one nobody has invented yet, and whatever else a beat needs. */
  public static Map<String, String> forged(Map<String, String> extra) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(EdgeHeaders.USER, FORGED_USER);
    headers.put(EdgeHeaders.USER_ID, FORGED_USER_ID);
    headers.put(EdgeHeaders.ROLES, FORGED_ROLES);
    headers.put(INVENTED_HEADER, "whatever a future hop might trust");
    headers.putAll(extra);
    return Map.copyOf(headers);
  }

  /** The same forgeries, behind a cookie idp vouches for. */
  public static Map<String, String> forgedWithSession(String value) {
    return forged(session(value));
  }

  /**
   * A browser's {@code Cookie} header. Two pairs, because a browser sends every cookie it holds for
   * the name and the session one is rarely first — which is also what makes {@code
   * EdgeHeaders.stripCookie}'s "only that pair" claim checkable.
   */
  public static Map<String, String> session(String value) {
    return Map.of("Cookie", "theme=dark; qits-session=" + value);
  }

  /** HTTP Basic, as the edge builds it for its own idp client. */
  public static String basic(String id, String secret) {
    return "Basic "
        + java.util.Base64.getEncoder()
            .encodeToString((id + ":" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  // --- how a label reads
  // ---------------------------------------------------------------------------

  /**
   * One INCOMING edge's label, exactly as {@code EdgeClient}'s tap builds it — the vhost beside the
   * path, because on this service the name is the routing decision and not decoration. Without it
   * the same path on two names would draw as one arrow, and the difference between them is the
   * whole subject.
   */
  public static String arriving(String method, String path, String host, int status) {
    return method + " " + path + " on " + host + " -> " + status;
  }

  /** The same for a GET, which is most of them. */
  public static String arriving(String path, String host, int status) {
    return arriving("GET", path, host, status);
  }

  /** One OUTGOING edge's label, as a stand-in's own recording builds it. */
  public static String proxied(String method, String path, Object status) {
    return method + " " + path + " -> " + status;
  }

  /** The same for a GET. */
  public static String proxied(String path, Object status) {
    return proxied("GET", path, status);
  }
}
