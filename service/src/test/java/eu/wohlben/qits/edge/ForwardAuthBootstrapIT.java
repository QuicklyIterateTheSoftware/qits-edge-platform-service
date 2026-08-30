package eu.wohlben.qits.edge;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.MockService;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.http.HttpMethod;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The whole service as it is <b>packaged</b>, with the browser gate ON, in front of a service that
 * records what arrived — the one posture this repository's suite has never had, and the only one in
 * which the platform's central trust claim is a fact rather than a sentence.
 *
 * <p>That claim is the reason this file exists, and it is the oldest class of this catalogue. Half
 * the fleet's CLAUDE.md files say some version of "the edge asserts {@code X-Qits-User} / {@code
 * X-Qits-User-Id} / {@code X-Qits-Roles} and every service behind it believes them
 * unconditionally", and each of those services tests the BELIEVING half against headers its own
 * suite wrote. Nobody tests the ASSERTING half except this repository, and until this catalogue
 * existed it did so with {@code @QuarkusTest} alone: {@code EdgeSessionGateTest} proves the
 * strip-then-inject against in-process stub upstreams inside the same JVM as the router. What only
 * a launched process has is this:
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
 *       StoryUpstream}, a separate process' worth of separation from the router: what it recorded
 *       is what a real service would have believed. An in-process stub and a real proxied socket
 *       are the same assertion right up until {@code vertx-http-proxy} copies a header map, and
 *       copying the header map is the whole subject.
 *   <li><b>The refusals are the real ones.</b> No dev-user, no synthetic identity, no test security
 *       — the launched process authenticates a cookie against idp or it refuses, and the two shapes
 *       of refusal (the login redirect a browser can follow, the {@code WWW-Authenticate} challenge
 *       docker acts on) are what a caller outside the platform actually receives.
 *   <li><b>Health and the door answer before any of it.</b> Both are properties of route ORDER in a
 *       booted Vert.x router — {@code EdgeRouter.ROUTE_ORDER} and the explicit {@code /q} skip —
 *       and a packaged process is where an ordering mistake would show.
 * </ul>
 *
 * <p><b>This class runs FIRST, and that is load-bearing.</b> A cumulative recording is attributed
 * by a cursor, so anything the launched process asked its far side before a story ran lands in
 * whichever story drains first. Every other story in the catalogue carries
 * {@code @UserflowRunsAfter(ForwardAuthBootstrapIT.class)} so that whatever a boot produces belongs
 * to the story that is about the boot.
 *
 * <p><b>What is NOT under test here, and must not be read into these two reports.</b> The strip
 * proven below is the one on the path where the edge <i>asserts an identity of its own</i> — a
 * validated browser session. The two paths that assert none — a MACHINE credential, and a read a
 * deployment opened through {@code qits.edge.auth.anonymous-read-apps} — strip the namespace too
 * and assert nothing into it, and that is {@code AnonymousReadIT}'s subject rather than this one's.
 * It is worth saying which way round it is: the strip is <b>unconditional</b> since it moved into
 * {@code EdgeRouter.proxy}, because the strip is the whole basis of a forward-auth service's trust
 * and cannot depend on there being a session to replace the value with.
 *
 * <p><b>The far side is two recorders, and they are two KINDS of recorder.</b> One is a {@link
 * StoryUpstream} impersonating <b>qits-projects</b> — a Vert.x server, because the stories in this
 * catalogue need response headers, non-JSON bodies, chunked answers, an outage arm and a WebSocket
 * upgrade, none of which a canned-JSON stand-in can do. The other is qits-service-mock's {@code
 * MockService} standing in for <b>qits-platform-idp</b>, which is exactly what that library is for:
 * one canned JSON answer per route, recorded. What the idp mock cannot stand in for is telling one
 * cookie from another — a canned stub answers every token the same way — and it does not need to:
 * {@code EdgeSessionGateTest}'s stub idp carries three sessions, a revocation and an outage, and
 * pins that half against the suite's own JVM. What is proven here and nowhere else is that a
 * launched artifact asks at all, over a real socket, with the credential a deployment gave it.
 *
 * <p><b>The requests are driven by {@link EdgeClient} rather than by rest-assured</b>, for the
 * reason written on that class: this service routes on {@code Host}, and rest-assured derives that
 * header from the URL it was given. rest-assured is used for the health probe alone, where the name
 * genuinely does not matter.
 *
 * <p><b>An absence is never an edge.</b> "The refused request never reached the service" is the
 * sharpest claim in this file and it is a claim about traffic that did NOT happen — the diagram can
 * only draw traffic that did. It stays exactly what it is: a count over the upstream's own
 * recording, with a note beside it. Read the two graphs together and the absence is legible anyway:
 * four requests arrive at the edge in the first story and only two leave it.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ForwardAuthBootstrapIT {

  static final String IDENTITY_SLUG =
      "a-forged-identity-never-reaches-the-service-and-the-one-idp-vouched-for-does";

  static final String DOOR_SLUG = "the-door-serves-nothing-and-a-health-probe-is-never-gated";

  @BeforeAll
  static void tapTheFarSide() {
    StoryNetwork.install();
  }

  @AfterAll
  static void closeClient() {
    StoryEdge.close();
  }

  @UserStory(
      value = "A forged identity never reaches the service, and the one idp vouched for does",
      category = StoryTarget.CATEGORY)
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
  @Order(1)
  void aForgedIdentityIsRefusedAndAValidatedOneIsAssertedInItsPlace(Interactions story) {
    StoryUpstream upstream = StoryUpstream.attach(StoryTarget.PROJECTS);

    story.note(
        "qits-platform-edge starts as the platform's only published port, with the browser gate on,"
            + " in front of four services and beside the identity provider it introspects at");
    given().get(StoryTarget.READY).then().statusCode(200).body("status", equalTo("UP"));

    // --- (1) the stranger, in a browser. A navigation is the one request that can render a login
    // page, so it is the one refusal that is a redirect. The whole path and query come back as
    // return_path so the page can put her where she was going; the return host is checked against
    // the browser-host allow-list rather than reflected, and the origin is the door's, because no
    // deployment here has published a host for whoever owns /idp/login.
    //
    // The actor is named BEFORE the call, and here it has to be: a stranger's browser, a machine
    // client and a logged-in person differ by headers, and every one of them sends the SAME three
    // forgeries. Nothing on the wire could pick the name out.
    NetworkCapture.actor("a stranger's browser");
    EdgeClient.Answer navigation =
        StoryEdge.client()
            .send(
                HttpMethod.GET,
                StoryTarget.PROJECTS_HOST,
                StoryTarget.REFUSED_NAVIGATION_PATH,
                null,
                StoryTarget.forged(Map.of("Sec-Fetch-Mode", "navigate", "Accept", "text/html")));
    assertEquals(302, navigation.status());
    assertEquals(
        StoryTarget.CANONICAL_ORIGIN
            + "/idp/login?return_host="
            + StoryTarget.PROJECTS_HOST
            + "&return_path=%2Fprojects%2Fruns%2F7",
        navigation.headers().get("location"),
        "a refused navigation must carry the name and path it was trying to reach");
    assertEquals(
        "no-store",
        navigation.headers().get("cache-control"),
        "a cached redirect would keep sending a logged-in browser back to the login page");
    assertEquals(
        0,
        upstream.requestsTo(StoryTarget.REFUSED_NAVIGATION_PATH),
        "a refused navigation must not reach the service, forged headers or not");
    // "…and it never reached the service" is an ABSENCE: the diagram can only draw traffic that
    // happened, so the counted assertion above is the proof and this note carries the claim. Read
    // the two graphs together and it is legible anyway — this request has no partner leaving the
    // edge.
    story
        .note(
            "a browser navigating with forged X-Qits-* headers and no session is sent to the login"
                + " page, carrying the name and path it was trying to reach — and the request never"
                + " reached the service at all")
        .as("a-forged-navigation-is-sent-to-the-login-page");

    // --- (2) the same stranger as a machine. No Sec-Fetch-Mode and no Accept for HTML, so this is
    // not a navigation and a 302 would be useless to it. The two challenges and their ORDER are the
    // contract: docker walks them and acts on the first scheme it knows, and a Basic challenge in
    // front would stop the token flow being used at all.
    NetworkCapture.actor("a machine client with no credential");
    EdgeClient.Answer challenged =
        StoryEdge.client()
            .send(
                HttpMethod.GET,
                StoryTarget.PROJECTS_HOST,
                StoryTarget.REFUSED_MACHINE_PATH,
                null,
                StoryTarget.forged(Map.of("Accept", "application/json")));
    assertEquals(401, challenged.status());
    List<String> challenges = challenged.headerValues("WWW-Authenticate");
    assertEquals(2, challenges.size(), "one door, described to two kinds of client");
    assertEquals(
        "Bearer realm=\"http://"
            + StoryTarget.PROJECTS_HOST
            + EdgeAuth.TOKEN_PATH
            + "\",service=\""
            + StoryTarget.PROJECTS_HOST
            + "\"",
        challenges.get(0),
        "docker acts on the first scheme it recognises, and it is told where to buy a token");
    assertEquals("Basic realm=\"" + StoryTarget.PROJECTS_HOST + "\"", challenges.get(1));
    assertTrue(
        challenged.body().contains("UNAUTHORIZED"),
        "the refusal is the Distribution spec's envelope, never an HTML page: "
            + challenged.body());
    assertEquals(
        0,
        upstream.requestsTo(StoryTarget.REFUSED_MACHINE_PATH),
        "a challenged machine request must not reach the service either");
    story
        .note(
            "a machine client with nothing to offer gets the WWW-Authenticate challenge docker acts"
                + " on — Bearer first, because docker takes the first scheme it recognises, Basic"
                + " behind it for maven and npm — and it does not reach the service either")
        .as("a-machine-client-is-challenged-not-redirected");

    // --- (3) the session. The same forged headers, now behind a cookie idp vouches for.
    long askedBefore = StoryNetwork.introspections();
    NetworkCapture.actor("a logged-in person");

    EdgeClient.Answer served =
        StoryEdge.client()
            .send(
                HttpMethod.GET,
                StoryTarget.PROJECTS_HOST,
                StoryTarget.IDENTITY_PATH,
                null,
                StoryTarget.forgedWithSession(StoryTarget.SESSION));
    assertEquals(200, served.status());
    story
        .note(
            "the same forgeries again, now behind a cookie — and this time the request is SERVED,"
                + " which is what makes the assertions below about identity rather than about"
                + " access")
        .as("a-session-arrives-carrying-forgeries");
    story
        .note(
            "the cookie is opaque here — 256 random bits, stored hashed at idp — so this process"
                + " asks, over a socket, presenting its OWN client credential: an introspection"
                + " endpoint anyone could call would be an oracle about everybody's session")
        .as("the-cookie-is-introspected-at-idp");

    // The assertion the whole platform's trust model rests on, made on the SERVICE's own recording:
    // what arrived is what a real qits service would have believed.
    StoryUpstream.Request arrived = upstream.onlyRequestTo(StoryTarget.IDENTITY_PATH);
    assertEquals(
        StoryTarget.SESSION_USER,
        arrived.header(EdgeHeaders.USER),
        "the service must be told who idp vouched for, never who the caller claimed to be");
    assertEquals(StoryTarget.SESSION_USER_ID, arrived.header(EdgeHeaders.USER_ID));
    assertEquals(StoryTarget.SESSION_ROLES_HEADER, arrived.header(EdgeHeaders.ROLES));
    assertEquals(
        StoryUpstream.ABSENT,
        arrived.header(StoryTarget.INVENTED_HEADER),
        "the rule is the `X-Qits-` prefix, so a reserved header nobody has thought of yet is"
            + " stripped too — a list of three names would pass this test and fail in a year");
    // The cookie itself travels on, and that is deliberate on a name a browser holds a session for:
    // the service behind it is an ordinary qits service and the browser will make its next request
    // with the cookie anyway. It is the MACHINE vhosts that must remove it — AnonymousReadIT.
    assertTrue(
        arrived.header("Cookie").contains("qits-session="),
        "a browser's own credential stays with it on the name it is browsing: "
            + arrived.header("Cookie"));
    // The headers are the whole subject and no diagram can carry them: an edge says a request
    // happened and what it was answered, never what it contained. The proof is the assertions
    // above, made on the SERVICE's own recording; the note is what a reader needs beside the arrow.
    story
        .note(
            "the service is told X-Qits-User: "
                + StoryTarget.SESSION_USER
                + " — who idp vouched for, never who the caller claimed to be. All three forgeries"
                + " are gone, and so is a reserved header nobody has invented yet: the rule is the"
                + " `X-Qits-` prefix, because a list of names is a thing somebody extends a day"
                + " late")
        .as("the-service-is-told-the-vouched-for-identity");

    // The edge asked, and it asked as itself. Without its own credential in that call, idp's
    // introspection endpoint would be an oracle anybody on the network could ask about any cookie.
    MockService.RecordedRequest asked = StoryNetwork.onlyIntrospection();
    assertEquals("POST", asked.method());
    assertEquals(
        StoryTarget.basic(StoryTarget.EDGE_CLIENT_ID, StoryTarget.EDGE_CLIENT_SECRET),
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
        StoryEdge.client()
            .send(
                HttpMethod.GET,
                StoryTarget.PROJECTS_HOST,
                StoryTarget.SECOND_PATH,
                null,
                StoryTarget.forgedWithSession(StoryTarget.SESSION))
            .status());
    assertEquals(
        StoryTarget.SESSION_USER,
        upstream.onlyRequestTo(StoryTarget.SECOND_PATH).header(EdgeHeaders.USER),
        "the cached belief must assert the same identity the call established");
    assertEquals(
        askedBefore + 1,
        StoryNetwork.introspections(),
        "two requests behind one cookie are one introspection — every request a browser makes"
            + " carries it, so a gate that asked each time would put idp on all of them");
    // Another absence, and the sharpest one on the diagram: the second read has a partner leaving
    // the edge to the upstream and NO second arrow to idp. The count above is the proof.
    story
        .note(
            "a second read behind the same cookie is served from the belief the first one bought —"
                + " every image and every poll a logged-in page makes carries that cookie, so a"
                + " gate that asked each time would put idp on the path of all of them")
        .as("a-second-read-is-served-without-asking-idp-again");
  }

  @UserStory(
      value = "The door serves nothing, and a health probe is never gated",
      category = StoryTarget.CATEGORY)
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
  @Order(2)
  void theDoorRefusesEverybodyAlikeWhileHealthAnswersEverybody(Interactions story) {
    StoryUpstream upstream = StoryUpstream.attach(StoryTarget.PROJECTS);

    NetworkCapture.actor("a visitor");

    // --- (1) the door, to a stranger. GET / is the one path a door has an answer to, and here it
    // is the 404: the redirect it would otherwise send needs a deployment to have published a host
    // for qits-projects, and this run has published none.
    EdgeClient.Answer door = StoryEdge.client().get(StoryTarget.DOOR_HOST, StoryTarget.DOOR_PATH);
    assertEquals(404, door.status());
    assertTrue(
        door.body().contains("This name is the environment door and serves nothing"), door.body());
    assertTrue(
        door.body().contains("`<app>." + StoryTarget.DOOR_HOST + "`"),
        "the refusal must name the shape every service IS on, or it is a dead end: " + door.body());
    assertEquals(0, upstream.requestsTo(StoryTarget.DOOR_PATH), "the door proxies nothing");
    story
        .note(
            "an environment's own name is a DOOR and a door serves nothing — the 404 names the"
                + " shape every service IS on (`<app>."
                + StoryTarget.DOOR_HOST
                + "`), which is the difference between a refusal and a dead end")
        .as("the-door-serves-nothing");

    // --- (2) the door, to somebody logged in. The same answer, which is the point: the door does
    // not gate, so a session cannot open it and its absence cannot close it. A service route asked
    // for here is 404 too — that route exists on its OWNER's name and nowhere else.
    NetworkCapture.actor("a logged-in person");
    EdgeClient.Answer doorWithSession =
        StoryEdge.client()
            .send(
                HttpMethod.GET,
                StoryTarget.DOOR_HOST,
                StoryTarget.DOOR_SERVICE_PATH,
                null,
                StoryTarget.session(StoryTarget.DOOR_SESSION));
    assertEquals(404, doorWithSession.status());
    assertEquals(
        0,
        upstream.requestsTo(StoryTarget.DOOR_SERVICE_PATH),
        "a service's route does not travel to the environment's own name, session or no session");
    // Two callers, one name, the same 404 — and the diagram draws them as two arrows precisely
    // because the actors differ, which is the whole statement: a session cannot open the door and
    // its absence cannot close it.
    story
        .note(
            "a perfectly good session gets the same 404: the door does not gate, because there is"
                + " nothing behind it to gate. A service's route lives on its OWNER's name and"
                + " nowhere else")
        .as("the-door-gates-nothing-because-it-serves-nothing");

    // --- (3) an app-shaped name nobody claims. NOT a fall-through: the name was aimed at a
    // service, and no configuration and no deployment holds it. The 404 names the label and says
    // the environment it read was fine, which is the difference between a typo and an outage.
    NetworkCapture.actor("a mistyped client");
    EdgeClient.Answer unclaimed =
        StoryEdge.client().get(StoryTarget.UNCLAIMED_HOST, StoryTarget.IDENTITY_PATH);
    assertEquals(404, unclaimed.status());
    assertTrue(
        unclaimed.body().contains("`nosuchservice` is not an application this edge routes"),
        unclaimed.body());
    assertTrue(
        unclaimed
            .body()
            .contains("the environment `" + StoryTarget.ENVIRONMENT + "` was read from the name"),
        unclaimed.body());
    // The same path as the first story's served read, on a name nobody claims — which is exactly
    // why the vhost is in the label and not only in the note: without it these two would be one
    // arrow, and the difference between them is the whole beat.
    story
        .note(
            "an app-shaped name nobody claims is a 404 that names the label and says the"
                + " environment it read was fine — the difference between a typo and an outage."
                + " Falling through would hand exactly those requests to the hop that does not"
                + " check them")
        .as("an-unclaimed-service-name-is-refused-rather-than-forwarded");

    // --- (4) health, on a gated service host, with no credential of any kind. This is the one
    // request on that name that must not meet the gate, and it is answered by this process rather
    // than proxied: the `/q` prefix is skipped explicitly, before the Host name is even read for
    // routing.
    NetworkCapture.actor("an orchestrator");
    EdgeClient.Answer ready = StoryEdge.client().get(StoryTarget.PROJECTS_HOST, StoryTarget.READY);
    assertEquals(200, ready.status(), "a readiness probe carries no session and never can");
    assertTrue(ready.body().contains("\"status\""), ready.body());
    assertTrue(ready.body().contains("UP"), ready.body());
    assertTrue(
        ready.body().contains("deployment-projection"),
        "readiness says what a booted edge is ready FOR: " + ready.body());
    assertEquals(200, StoryEdge.client().get(StoryTarget.PROJECTS_HOST, StoryTarget.LIVE).status());
    assertEquals(
        0,
        upstream.requestsTo(StoryTarget.READY),
        "the edge's own surface is the one thing that never leaves the process");
    // The probe is NOT skipped by this tap, unlike every sibling service's, and that is deliberate:
    // here health is the subject rather than the scaffolding. `/q` is skipped before the Host name
    // is even read for routing, and the two edges below — the gated service name and the door —
    // are what say so.
    story
        .note(
            "health is answered by this process rather than proxied, on a GATED name, with no"
                + " credential of any kind: an orchestrator carries no session and never can, and"
                + " this is the container that binds the host's only published port")
        .as("health-is-never-gated-and-never-proxied");

    // …and on the door's name too, because a probe does not know or care which vhost it hit.
    assertEquals(200, StoryEdge.client().get(StoryTarget.DOOR_HOST, StoryTarget.READY).status());
    story
        .note(
            "…and on the door's name too, because a probe does not know or care which vhost it hit")
        .as("health-answers-whatever-name-was-used");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    // assertComplete also proves the network section: the sidecar's edges are canonical, the
    // networkHash recomputes from them, and every mermaid line is in the markdown.
    ReportAssertions.assertComplete(StoryTarget.CATEGORY, IDENTITY_SLUG, UserflowReport.PASSED);

    // --- the identity story's whole graph, all three ends ----------------------------------------
    // In, observed by EdgeClient. Four requests, four callers, four names for the same door — and
    // the run id in the navigation's path is scrubbed to {id}, because it is run-local and the
    // story's networkHash must not move with it.
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        IDENTITY_SLUG,
        "http",
        "a stranger's browser",
        StoryTarget.SERVICE,
        StoryTarget.arriving("/projects/runs/{id}", StoryTarget.PROJECTS_HOST, 302));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        IDENTITY_SLUG,
        "http",
        "a machine client with no credential",
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.REFUSED_MACHINE_PATH, StoryTarget.PROJECTS_HOST, 401));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        IDENTITY_SLUG,
        "http",
        "a logged-in person",
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.IDENTITY_PATH, StoryTarget.PROJECTS_HOST, 200));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        IDENTITY_SLUG,
        "http",
        "a logged-in person",
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.SECOND_PATH, StoryTarget.PROJECTS_HOST, 200));
    // Onward to the service, drained from ITS recording. Two of the four arrived, which is the
    // refusals' whole proof read off the diagram: nothing left the edge for the other two.
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        IDENTITY_SLUG,
        "http",
        StoryTarget.SERVICE,
        StoryTarget.PROJECTS,
        StoryTarget.proxied(StoryTarget.IDENTITY_PATH, 200));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        IDENTITY_SLUG,
        "http",
        StoryTarget.SERVICE,
        StoryTarget.PROJECTS,
        StoryTarget.proxied(StoryTarget.SECOND_PATH, 200));
    // And ONE ask of idp, for two reads behind one cookie. The count in the story is what makes
    // that "one", since a second identical ask would be the same edge.
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        IDENTITY_SLUG,
        "http",
        StoryTarget.SERVICE,
        StoryTarget.IDP,
        StoryTarget.proxied("POST", StoryTarget.INTROSPECT, 200));
    // EXACTLY those seven. This is the assertion that catches a refused request quietly starting to
    // be forwarded, or the edge dialling idp for something no story asked it to — neither of which
    // any presence check above could see.
    ReportAssertions.assertEdgeCount(StoryTarget.CATEGORY, IDENTITY_SLUG, 7);
    ReportAssertions.assertOnlyEdgesFrom(
        StoryTarget.CATEGORY,
        IDENTITY_SLUG,
        List.of(
            "a stranger's browser",
            "a machine client with no credential",
            "a logged-in person",
            StoryTarget.SERVICE));
    // The cookie is the credential this story is about, and it must be nowhere in the report: not
    // in a label, not in a note, not in a step.
    ReportAssertions.assertNotLeaked(StoryTarget.CATEGORY, IDENTITY_SLUG, StoryTarget.SESSION);
    ReportAssertions.assertNotLeaked(
        StoryTarget.CATEGORY, IDENTITY_SLUG, StoryTarget.EDGE_CLIENT_SECRET);

    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, IDENTITY_SLUG, "a-forged-navigation-is-sent-to-the-login-page");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, IDENTITY_SLUG, "a-machine-client-is-challenged-not-redirected");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, IDENTITY_SLUG, "a-session-arrives-carrying-forgeries");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, IDENTITY_SLUG, "the-cookie-is-introspected-at-idp");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, IDENTITY_SLUG, "the-service-is-told-the-vouched-for-identity");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, IDENTITY_SLUG, "a-second-read-is-served-without-asking-idp-again");

    ReportAssertions.assertComplete(StoryTarget.CATEGORY, DOOR_SLUG, UserflowReport.PASSED);

    // --- the door story's whole graph ------------------------------------------------------------
    // Six arrivals and NOTHING leaving: no upstream edge and no idp edge anywhere in this story,
    // which is the door's entire claim. assertNoEdgesTo is what states it — a presence check cannot
    // assert that a hop did not happen, and here it PAYS, because every one of those services was
    // live and answering throughout.
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        DOOR_SLUG,
        "http",
        "a visitor",
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.DOOR_PATH, StoryTarget.DOOR_HOST, 404));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        DOOR_SLUG,
        "http",
        "a logged-in person",
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.DOOR_SERVICE_PATH, StoryTarget.DOOR_HOST, 404));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        DOOR_SLUG,
        "http",
        "a mistyped client",
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.IDENTITY_PATH, StoryTarget.UNCLAIMED_HOST, 404));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        DOOR_SLUG,
        "http",
        "an orchestrator",
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.READY, StoryTarget.PROJECTS_HOST, 200));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        DOOR_SLUG,
        "http",
        "an orchestrator",
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.LIVE, StoryTarget.PROJECTS_HOST, 200));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        DOOR_SLUG,
        "http",
        "an orchestrator",
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.READY, StoryTarget.DOOR_HOST, 200));
    ReportAssertions.assertEdgeCount(StoryTarget.CATEGORY, DOOR_SLUG, 6);
    ReportAssertions.assertNoEdgesTo(StoryTarget.CATEGORY, DOOR_SLUG, StoryTarget.PROJECTS);
    ReportAssertions.assertNoEdgesTo(StoryTarget.CATEGORY, DOOR_SLUG, StoryTarget.IDP);
    ReportAssertions.assertNotLeaked(StoryTarget.CATEGORY, DOOR_SLUG, StoryTarget.DOOR_SESSION);

    ReportAssertions.assertStepId(StoryTarget.CATEGORY, DOOR_SLUG, "the-door-serves-nothing");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, DOOR_SLUG, "the-door-gates-nothing-because-it-serves-nothing");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY,
        DOOR_SLUG,
        "an-unclaimed-service-name-is-refused-rather-than-forwarded");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, DOOR_SLUG, "health-is-never-gated-and-never-proxied");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, DOOR_SLUG, "health-answers-whatever-name-was-used");
  }
}
