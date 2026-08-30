package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>The one decision this process makes before it makes any other: which service a request is
 * for.</b>
 *
 * <p>It is made from the {@code Host} header and from nothing else. There is no route table beyond
 * the application list and the deployment projection, no path is rewritten, no body is read — a
 * name selects an <i>index into a fixed list</i> and never contributes a character to an address,
 * which is the whole SSRF guard. Everything else in this catalogue happens downstream of this
 * decision, and every one of those stories would still pass if the edge sent every request to the
 * same place. This is the one that says it does not.
 *
 * <p><b>Which is why the far side is TWO ordinary services rather than one.</b> "The request
 * reached a service" is a status code; "the request reached <i>this</i> service and not that one"
 * is only sayable if there is a that one, and only provable on the receiver's own recording. So
 * {@code qits-projects} and {@code qits-docs} are configured identically, gated identically, and
 * both answer the same path — and the story asks for that path on both names with the same cookie,
 * so the name is the only thing that differs between the two requests.
 *
 * <p><b>The third name is the one that shows a path is not a route.</b> {@code
 * mirror.dev.example.com} is a machine vhost, and it has no such path: the 404 the caller gets is
 * the MIRROR's own, generated one hop further in, not a refusal by the edge. On a path-routed proxy
 * that request would have been recognised and sent to whoever owns {@code /projects}; here nobody
 * owns a path at all.
 *
 * <p><b>This is also why every label in this catalogue carries the vhost.</b> Two of the three
 * requests below are byte-identical apart from one header. A label built from the method and the
 * path would draw them as one arrow, and the difference between them is the entire subject.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class VhostRoutingIT {

  static final String SLUG = "one-name-one-service-the-host-header-is-the-whole-routing-decision";

  static final String BROWSING_PROJECTS = "a person reading a project";

  static final String BROWSING_DOCS = "the same person, one link later, reading the docs";

  static final String PACKAGE_CLIENT = "a package client on the mirror's name";

  @BeforeAll
  static void tapTheFarSide() {
    StoryNetwork.install();
  }

  @AfterAll
  static void closeClient() {
    StoryEdge.close();
  }

  @UserStory(
      value = "One name, one service: the Host header is the whole routing decision",
      category = StoryTarget.CATEGORY)
  @UserStoryDescription(
      """
      Every service on this platform is reached on a name of its own — `<app>.<env>.<domain>` — and
      the front door does exactly one thing with that name: it looks it up. There is no path
      knowledge in this process at all. It does not know that `/projects/api` belongs to
      qits-projects, and it must not: a name selects an index into a fixed list, so there is no
      string a client can send that reaches a host nobody configured.

      The story is one person following one link and then another, and a third caller who is not a
      person. She asks `projects.dev.example.com` for a version, and qits-projects answers. She
      follows a link to `docs.dev.example.com` and asks for **the same path**, with the same
      cookie, from the same browser — and a completely different process answers, which is a fact
      neither of the two status codes can tell you and both of the recordings can.

      Then a package client asks the mirror's name for that same path. The mirror is a real service
      and it is reached, and it has never heard of that route — so the 404 comes back from one hop
      further in, with the mirror's own words in it. That is the difference a path-routed proxy
      cannot draw: there, the request would have been recognised and handed to whoever owns
      `/projects`. Here nobody owns a path.

      One cookie, one introspection: the edge asked idp about her once and served both of her reads
      from the belief it bought, because the two names are two services and one person.
      """)
  @UserflowRunsAfter(ForwardAuthBootstrapIT.class)
  void theHostHeaderPicksTheService(Interactions story) {
    StoryUpstream projects = StoryUpstream.attach(StoryTarget.PROJECTS);
    StoryUpstream docs = StoryUpstream.attach(StoryTarget.DOCS);
    StoryUpstream mirror = StoryUpstream.attach(StoryTarget.MIRROR);
    long askedBefore = StoryNetwork.introspections();

    // --- (1) the first name.
    NetworkCapture.actor(BROWSING_PROJECTS);
    EdgeClient.Answer fromProjects =
        StoryEdge.client()
            .send(
                HttpMethod.GET,
                StoryTarget.PROJECTS_HOST,
                StoryTarget.ROUTED_PATH,
                null,
                StoryTarget.session(StoryTarget.ROUTING_SESSION));
    assertEquals(200, fromProjects.status());
    assertTrue(
        fromProjects.body().contains(StoryTarget.PROJECTS),
        "the body must come from the service the name belongs to: " + fromProjects.body());

    // --- (2) the SAME path, the same cookie, the same browser — one label of the name different.
    NetworkCapture.actor(BROWSING_DOCS);
    EdgeClient.Answer fromDocs =
        StoryEdge.client()
            .send(
                HttpMethod.GET,
                StoryTarget.DOCS_HOST,
                StoryTarget.ROUTED_PATH,
                null,
                StoryTarget.session(StoryTarget.ROUTING_SESSION));
    assertEquals(200, fromDocs.status());
    assertTrue(
        fromDocs.body().contains(StoryTarget.DOCS),
        "a different name must be a different process: " + fromDocs.body());

    // Both recordings, which is where the claim actually lives: two processes each hold exactly one
    // request for this path, and neither holds the other's.
    StoryUpstream.Request atProjects = projects.onlyRequestTo(StoryTarget.ROUTED_PATH);
    StoryUpstream.Request atDocs = docs.onlyRequestTo(StoryTarget.ROUTED_PATH);
    // The client's own name reaches the upstream. vertx-http-proxy leaves a proxied request's
    // authority unset and the client then fills Host in from the socket it opened, so without
    // EdgeHeaders' first job every upstream would see a loopback address and no redirect, cookie
    // domain or absolute URL it generated could be right.
    assertEquals(
        StoryTarget.PROJECTS_HOST,
        atProjects.header("Host"),
        "the name the browser typed is what the upstream has to build its links from");
    assertEquals(StoryTarget.DOCS_HOST, atDocs.header("Host"));
    assertEquals(
        StoryTarget.PROJECTS_HOST,
        atProjects.header(EdgeHeaders.HOST),
        "and it is described again as X-Forwarded-Host, for the hop that reads that instead");
    assertEquals(StoryTarget.DOCS_HOST, atDocs.header(EdgeHeaders.HOST));
    // Both were served as the same person, which is what makes the difference between them purely
    // the routing decision rather than anything about who asked.
    assertEquals(StoryTarget.SESSION_USER, atProjects.header(EdgeHeaders.USER));
    assertEquals(StoryTarget.SESSION_USER, atDocs.header(EdgeHeaders.USER));
    story
        .note(
            "the same path, the same cookie and the same browser reach TWO different services — the"
                + " name is the whole decision, and each service's own recording is what says which"
                + " of them received the request")
        .as("the-same-path-on-two-names-is-two-services");

    assertEquals(
        askedBefore + 1,
        StoryNetwork.introspections(),
        "one cookie is one introspection however many names it is spent on");
    story
        .note(
            "one person, two names, one ask of idp: the belief the first read bought is what serves"
                + " the second, because a session belongs to a person and not to a vhost")
        .as("two-names-behind-one-cookie-are-one-introspection");

    // --- (3) a name that IS routed, to a service that has no such path. The 404 is the mirror's.
    NetworkCapture.actor(PACKAGE_CLIENT);
    EdgeClient.Answer fromMirror =
        StoryEdge.client().get(StoryTarget.MIRROR_HOST, StoryTarget.ROUTED_PATH);
    assertEquals(404, fromMirror.status());
    assertTrue(
        fromMirror.body().contains(StoryTarget.MIRROR),
        "the refusal must be the SERVICE's, one hop further in, not the edge's: "
            + fromMirror.body());
    StoryUpstream.Request atMirror = mirror.onlyRequestTo(StoryTarget.ROUTED_PATH);
    assertEquals("404", atMirror.status(), "the mirror answered it, which is why it is recorded");
    story
        .note(
            "the same path again on a machine vhost: it REACHES qits-platform-mirror, which has"
                + " never heard of that route, so the 404 is written one hop further in. A"
                + " path-routed proxy would have recognised the request and sent it to whoever owns"
                + " `/projects`; this process owns no path at all")
        .as("a-path-is-not-a-route-here");
  }

  @AfterAll
  static void theReportIsComplete() {
    ReportAssertions.assertComplete(StoryTarget.CATEGORY, SLUG, UserflowReport.PASSED);

    // Three arrivals, and the first two differ ONLY by the vhost in the label — which is exactly
    // why the vhost is in the label.
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        BROWSING_PROJECTS,
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.ROUTED_PATH, StoryTarget.PROJECTS_HOST, 200));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        BROWSING_DOCS,
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.ROUTED_PATH, StoryTarget.DOCS_HOST, 200));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        PACKAGE_CLIENT,
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.ROUTED_PATH, StoryTarget.MIRROR_HOST, 404));
    // …and three departures, to three DIFFERENT services. This is the picture: one path, three
    // arrows out, three destinations.
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        StoryTarget.SERVICE,
        StoryTarget.PROJECTS,
        StoryTarget.proxied(StoryTarget.ROUTED_PATH, 200));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        StoryTarget.SERVICE,
        StoryTarget.DOCS,
        StoryTarget.proxied(StoryTarget.ROUTED_PATH, 200));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        StoryTarget.SERVICE,
        StoryTarget.MIRROR,
        StoryTarget.proxied(StoryTarget.ROUTED_PATH, 404));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        StoryTarget.SERVICE,
        StoryTarget.IDP,
        StoryTarget.proxied("POST", StoryTarget.INTROSPECT, 200));
    // Exactly seven: three in, three out, one ask. A second introspection — a cache keyed by the
    // vhost rather than by the cookie — would be an eighth and would fail here.
    ReportAssertions.assertEdgeCount(StoryTarget.CATEGORY, SLUG, 7);
    ReportAssertions.assertOnlyEdgesFrom(
        StoryTarget.CATEGORY,
        SLUG,
        List.of(BROWSING_PROJECTS, BROWSING_DOCS, PACKAGE_CLIENT, StoryTarget.SERVICE));
    ReportAssertions.assertNotLeaked(StoryTarget.CATEGORY, SLUG, StoryTarget.ROUTING_SESSION);

    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, SLUG, "the-same-path-on-two-names-is-two-services");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, SLUG, "two-names-behind-one-cookie-are-one-introspection");
    ReportAssertions.assertStepId(StoryTarget.CATEGORY, SLUG, "a-path-is-not-a-route-here");
  }
}
