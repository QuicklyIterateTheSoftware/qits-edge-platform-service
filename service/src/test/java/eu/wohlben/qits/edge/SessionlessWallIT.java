package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.http.HttpMethod;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>Nothing a caller with no credential sends leaves this process — and that is a claim about
 * traffic which did not happen, so it is the one story in this catalogue whose paying assertions
 * are all negative.</b>
 *
 * <p>Every service behind this door has no external authentication of its own. It reads {@code
 * X-Qits-User} and believes it, and it answers whatever arrives. So a request that reaches an
 * upstream is a request that has been authorised, whatever it turns out to ask for — which makes
 * "it did not reach the upstream" the whole of what a refusal means here. A 401 whose body still
 * reached the service would be a leak with a green status code.
 *
 * <p><b>{@code assertNoEdgesTo} pays here, and it only pays because the far side is UP.</b> All
 * three services and the identity provider are running and answering throughout this story — the
 * one before it and the one after it both get 200s out of them — so an empty arrow set is a
 * statement about this process's decisions rather than about a fixture that was switched off.
 * {@code assertNoEdgesFrom(qits-platform-edge)} says the same thing once, in the strongest form the
 * framework has: <i>nothing left this process at all</i>.
 *
 * <p><b>Four kinds of caller meet the wall, because a wall that answers everybody the same way is
 * useless.</b> A browser navigating can render a login page and is sent to one. A logged-out tab's
 * background {@code fetch} cannot render anything and must not be handed a {@code Basic} challenge
 * — the browser would answer that with its own credential dialog, over whatever page the person is
 * looking at. A WebSocket handshake is the same problem with a worse failure: a 302 handed to one
 * kills the socket with nothing to read. And a machine client, which has no login page to be sent
 * to, gets the {@code WWW-Authenticate} challenge it can actually act on.
 *
 * <p><b>The socket beat is here rather than in the terminal story on purpose.</b> An upgrade takes
 * {@code EdgeWebSocketUpgrade}, the edge's own path, which never installs the interceptor chain —
 * it is the plane on which a forged identity used to cross this process. What proves the gate runs
 * before that path is a handshake that is refused: it is sent as a plain request carrying the
 * upgrade headers, so the refusal has a real status and draws as the plain 401 it was, rather than
 * as a socket that was never opened.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class SessionlessWallIT {

  static final String SLUG = "the-401-wall-nothing-a-stranger-sends-leaves-this-process";

  static final String NAVIGATING = "a logged-out browser navigating";

  static final String BACKGROUND_FETCH = "a logged-out tab's background fetch";

  static final String OPENING_A_TERMINAL = "a logged-out browser opening a terminal";

  static final String PUSHING = "a package client pushing";

  @BeforeAll
  static void tapTheFarSide() {
    StoryNetwork.install();
  }

  @AfterAll
  static void closeClient() {
    StoryEdge.close();
  }

  @UserStory(
      value = "The 401 wall: nothing a stranger sends leaves this process",
      category = StoryTarget.CATEGORY)
  @UserStoryDescription(
      """
      Behind this door every service believes `X-Qits-User` without checking it and has no
      authentication of its own. So the only thing a refusal can mean here is that the request
      never arrived — a 401 whose body had already reached the service would be a leak wearing a
      status code.

      Four callers with nothing to offer arrive, and each is refused in the shape it can act on.

      A browser navigating to a page is sent to the login page, carrying the name and the path it
      was trying to reach, so it can be put back where it was going.

      A background `fetch` from a tab that is already open cannot render a login page, and it gets
      a 401 with the page's address in the body — and deliberately **no** `WWW-Authenticate`
      header, because a browser answers a `Basic` challenge by popping its own credential dialog
      over whatever the person happens to be looking at.

      A WebSocket handshake is refused the same way, and this is the plane that matters most: an
      upgrade takes a path of its own through this process, one that skips the interceptor chain
      entirely, and it is the path a forged identity used to cross on. The refusal happens before
      any of it.

      A package client pushing a layer has no login page to be sent to at all, so it gets the
      `WWW-Authenticate` challenge it understands. Its name is one whose READS a deployment opened
      to everyone — which is exactly why the write is refused: opening reads is not publishing a
      service.

      And the diagram is the assertion. Four services were up and answering throughout. Not one
      arrow leaves this process.
      """)
  @UserflowRunsAfter(ForwardAuthBootstrapIT.class)
  void nothingWithoutACredentialCrossesTheEdge(Interactions story) {
    StoryUpstream projects = StoryUpstream.attach(StoryTarget.PROJECTS);
    StoryUpstream docs = StoryUpstream.attach(StoryTarget.DOCS);
    StoryUpstream mirror = StoryUpstream.attach(StoryTarget.MIRROR);
    long askedBefore = StoryNetwork.introspections();

    // --- (1) a navigation. The one refusal that is a redirect, because it is the one request that
    // can render a page.
    NetworkCapture.actor(NAVIGATING);
    EdgeClient.Answer navigation =
        StoryEdge.client()
            .get(
                StoryTarget.PROJECTS_HOST,
                StoryTarget.WALL_NAVIGATION_PATH,
                Map.of("Sec-Fetch-Mode", "navigate", "Accept", "text/html"));
    assertEquals(302, navigation.status());
    assertEquals(
        StoryTarget.CANONICAL_ORIGIN
            + "/idp/login?return_host="
            + StoryTarget.PROJECTS_HOST
            + "&return_path=%2Fprojects%2Fsettings",
        navigation.headers().get("location"),
        "the login page has to be told where to put her back");
    assertEquals("no-store", navigation.headers().get("cache-control"));

    // …and the same wall on the OTHER service's name, which is what makes this a property of the
    // gate rather than of one vhost's configuration.
    EdgeClient.Answer elsewhere =
        StoryEdge.client()
            .get(
                StoryTarget.DOCS_HOST,
                StoryTarget.WALL_NAVIGATION_PATH,
                Map.of("Sec-Fetch-Mode", "navigate", "Accept", "text/html"));
    assertEquals(302, elsewhere.status());
    assertTrue(
        elsewhere.headers().get("location").contains("return_host=" + StoryTarget.DOCS_HOST),
        "every service's own name is its own return host: " + elsewhere.headers().get("location"));
    story
        .note(
            "a browser navigating with no session is sent to the login page on every service name"
                + " alike, each carrying its own name and path back — the gate is the door's, not"
                + " one vhost's configuration")
        .as("a-navigation-is-sent-to-the-login-page-on-every-name");

    // --- (2) a background fetch. It cannot render anything, so a redirect would be followed into
    // HTML it cannot use — and a Basic challenge would be answered by the browser's own dialog.
    NetworkCapture.actor(BACKGROUND_FETCH);
    EdgeClient.Answer background =
        StoryEdge.client()
            .get(
                StoryTarget.PROJECTS_HOST,
                StoryTarget.WALL_READ_PATH,
                Map.of("Sec-Fetch-Mode", "cors", "Accept", "application/json"));
    assertEquals(401, background.status());
    assertNull(
        background.headers().get("www-authenticate"),
        "a Basic challenge here would pop the browser's credential dialog over the page she is"
            + " looking at");
    assertTrue(
        background.body().contains("\"login\""),
        "the body names the login page so an SPA can send her there itself: " + background.body());
    story
        .note(
            "a logged-out tab's background fetch gets a 401 naming the login page and NO"
                + " WWW-Authenticate: a 302 would be followed into HTML it cannot use, and a Basic"
                + " challenge would be answered by the browser's own credential dialog")
        .as("a-background-fetch-is-refused-without-a-credential-dialog");

    // --- (3) the socket. Sent as a plain request carrying the upgrade headers, because a refused
    // handshake has a STATUS and a client-side WebSocket API throws that away.
    NetworkCapture.actor(OPENING_A_TERMINAL);
    EdgeClient.Answer refusedUpgrade =
        StoryEdge.client()
            .get(StoryTarget.PROJECTS_HOST, StoryTarget.WALL_TERMINAL_PATH, upgrade("websocket"));
    assertEquals(401, refusedUpgrade.status());
    assertTrue(
        refusedUpgrade.body().contains("\"login\""),
        "a browser's socket is refused with a login link, like its other fetches: "
            + refusedUpgrade.body());
    story
        .note(
            "the upgrade is refused before it reaches the edge's own WebSocket path — the one code"
                + " path that never installs the interceptor chain, and the one on which a forged"
                + " identity used to cross this process")
        .as("an-upgrade-meets-the-gate-before-it-meets-the-splice");

    // --- (4) a machine. It has no login page to be sent to; it gets the challenge it can act on.
    // And its name is the one whose READS are open, which is the point: a write is not a read.
    NetworkCapture.actor(PUSHING);
    EdgeClient.Answer push =
        StoryEdge.client()
            .send(
                HttpMethod.POST,
                StoryTarget.MIRROR_HOST,
                StoryTarget.MIRROR_WRITE,
                "",
                Map.of("Accept", "application/json"));
    assertEquals(401, push.status());
    List<String> challenges = push.headerValues("WWW-Authenticate");
    assertEquals(2, challenges.size(), "one door, described to two kinds of client");
    assertTrue(
        challenges.get(0).startsWith("Bearer realm=\"http://" + StoryTarget.MIRROR_HOST),
        "the realm is this vhost's own, so a token bought here opens here: " + challenges.get(0));
    story
        .note(
            "a write on a name whose READS a deployment opened is refused like any other: the"
                + " exemption is method-shaped, because a pull is a bootstrap step and a push is a"
                + " change to what the platform will run")
        .as("an-open-read-is-not-an-open-service");

    // --- the whole point, counted on the far side as well as drawn on the diagram.
    assertEquals(
        0,
        projects.requestsTo(StoryTarget.WALL_NAVIGATION_PATH)
            + projects.requestsTo(StoryTarget.WALL_READ_PATH)
            + projects.requestsTo(StoryTarget.WALL_TERMINAL_PATH),
        "nothing refused above may have reached qits-projects");
    assertEquals(
        0, docs.requestsTo(StoryTarget.WALL_NAVIGATION_PATH), "nor qits-docs, on its own name");
    assertEquals(
        0, mirror.requestsTo(StoryTarget.MIRROR_WRITE), "nor qits-platform-mirror, for the write");
    assertEquals(
        askedBefore,
        StoryNetwork.introspections(),
        "and idp was never asked: there was no cookie to ask about, so the gate refused without"
            + " putting the identity provider on the path of every anonymous request");
    story
        .note(
            "four refusals, four services up and answering, and NOTHING left this process — which"
                + " is what a refusal has to mean when every service behind the door believes"
                + " X-Qits-User without checking it")
        .as("not-one-arrow-leaves-the-edge");
  }

  /**
   * A complete WebSocket handshake, sent as an ordinary request. A client-side WebSocket API throws
   * the response of a refused upgrade away, and that response is exactly what this beat is about.
   *
   * @param fetchMode what a browser stamps on the handshake it opens — {@code websocket}, which is
   *     not {@code navigate}, which is why the refusal is a 401 and not a redirect that would kill
   *     the socket with nothing to read
   */
  private static Map<String, String> upgrade(String fetchMode) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Upgrade", "websocket");
    headers.put("Connection", "Upgrade");
    headers.put("Sec-WebSocket-Key", "AAAAAAAAAAAAAAAAAAAAAA==");
    headers.put("Sec-WebSocket-Version", "13");
    headers.put("Sec-Fetch-Mode", fetchMode);
    return Map.copyOf(headers);
  }

  @AfterAll
  static void theReportIsComplete() {
    ReportAssertions.assertComplete(StoryTarget.CATEGORY, SLUG, UserflowReport.PASSED);

    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        NAVIGATING,
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.WALL_NAVIGATION_PATH, StoryTarget.PROJECTS_HOST, 302));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        NAVIGATING,
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.WALL_NAVIGATION_PATH, StoryTarget.DOCS_HOST, 302));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        BACKGROUND_FETCH,
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.WALL_READ_PATH, StoryTarget.PROJECTS_HOST, 401));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        OPENING_A_TERMINAL,
        StoryTarget.SERVICE,
        // The session id is generated per terminal, so Labels rewrites it to {id} on the way in —
        // which is what keeps this story's networkHash from moving with a value nobody authored.
        StoryTarget.arriving(StoryTarget.TERMINAL_LABEL_PATH, StoryTarget.PROJECTS_HOST, 401));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        PUSHING,
        StoryTarget.SERVICE,
        StoryTarget.arriving("POST", StoryTarget.MIRROR_WRITE, StoryTarget.MIRROR_HOST, 401));
    ReportAssertions.assertEdgeCount(StoryTarget.CATEGORY, SLUG, 5);

    // The paying assertions. Every one of these services answered a 200 in the story before this
    // one and in the story after it, so an empty arrow set is this process's decision and not a
    // fixture that was off.
    ReportAssertions.assertNoEdgesTo(StoryTarget.CATEGORY, SLUG, StoryTarget.PROJECTS);
    ReportAssertions.assertNoEdgesTo(StoryTarget.CATEGORY, SLUG, StoryTarget.DOCS);
    ReportAssertions.assertNoEdgesTo(StoryTarget.CATEGORY, SLUG, StoryTarget.MIRROR);
    ReportAssertions.assertNoEdgesTo(StoryTarget.CATEGORY, SLUG, StoryTarget.IDP);
    // …and the same claim once, in the strongest form there is.
    ReportAssertions.assertNoEdgesFrom(StoryTarget.CATEGORY, SLUG, StoryTarget.SERVICE);
    ReportAssertions.assertOnlyEdgesFrom(
        StoryTarget.CATEGORY,
        SLUG,
        List.of(NAVIGATING, BACKGROUND_FETCH, OPENING_A_TERMINAL, PUSHING));

    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, SLUG, "a-navigation-is-sent-to-the-login-page-on-every-name");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, SLUG, "a-background-fetch-is-refused-without-a-credential-dialog");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, SLUG, "an-upgrade-meets-the-gate-before-it-meets-the-splice");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, SLUG, "an-open-read-is-not-an-open-service");
    ReportAssertions.assertStepId(StoryTarget.CATEGORY, SLUG, "not-one-arrow-leaves-the-edge");
  }
}
