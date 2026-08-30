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
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>A service that is not there is an ANSWER, not a wait.</b>
 *
 * <p>This process binds the host's only published port, so every failure mode it has is the
 * platform's failure mode. The one that costs most is not an error — it is silence: a request that
 * queues behind something and is never answered leaves a browser spinning, a {@code docker pull}
 * stalled and nothing in a log naming what it was waiting for. This repository has paid for that
 * twice: a Vert.x client pool that defaults to five connections behind an UNBOUNDED wait queue, so
 * one {@code docker push} starved a whole environment with nothing logged; and an upgrade path that
 * leaked a pool slot per attempt until all sixty-four were gone and plain GETs hung too. Both fixes
 * are bounds — {@code maxPoolSize}, {@code maxWaitQueueSize}, {@code ACQUIRE_TIMEOUT_MS} — and a
 * bound is only worth anything if the thing it bounds ends in a status code.
 *
 * <p><b>Two shapes of "not there", because they fail in two different places.</b> An address
 * nothing listens on is refused at connect, before a byte is written — the failure is in {@code
 * EdgeRouter.origin}, which is also the one line that logs which origin it was. A service that
 * accepted the connection and then went away is a failure mid-exchange, after the request has been
 * forwarded; the far side's own recording proves it arrived, and the caller is still answered.
 *
 * <p><b>The recovery beat is the one that makes the other two mean something.</b> An edge that
 * answered 502 to everything would pass both refusals. So the same name, the same cookie and the
 * same path are asked for again once the service is back, and the 200 says the outage cost the
 * route nothing.
 *
 * <p><b>The arm is cleared in a finally and again in {@code @AfterEach}</b>, because a stand-in
 * left dropping would fail every story after this one with a symptom that names none of them.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class UpstreamOutageIT {

  static final String SLUG = "a-service-that-is-not-there-is-an-answer-and-never-a-wait";

  /**
   * What {@code vertx-http-proxy} answers when it could not get an answer out of an origin — the
   * one number, spelled once, so the assertion and the edge label can never drift apart.
   */
  static final int UPSTREAM_GONE = 502;

  static final String DURING_A_REPLACEMENT = "a person whose service is being replaced";

  static final String AFTERWARDS = "the same person, a moment later";

  static final String ON_A_MISCONFIGURED_NAME = "a person on a name that points nowhere";

  @BeforeAll
  static void tapTheFarSide() {
    StoryNetwork.install();
  }

  @AfterEach
  void giveTheServiceBack() {
    // Belt as well as the braces in the story's own finally: a stand-in left dropping would fail
    // every later story with a symptom that names none of them.
    StoryUpstream.attach(StoryTarget.PROJECTS).dropping(false);
  }

  @AfterAll
  static void closeClient() {
    StoryEdge.close();
  }

  @UserStory(
      value = "A service that is not there is an answer, and never a wait",
      category = StoryTarget.CATEGORY)
  @UserStoryDescription(
      """
      Containers are replaced. For a few seconds a name refuses, drops, or accepts a connection and
      then says nothing at all — and this process is in front of every one of them, holding the
      host's only published port. What it does in those seconds is the whole difference between an
      outage somebody can read and an outage that looks like the platform hanging.

      A person reloads a page while her service is being replaced. The request reaches the
      container — its own recording proves that, and the recording is written before the connection
      goes away, because a recording written afterwards would miss exactly this exchange — and then
      there is nothing on the wire. She is answered anyway: a 502, promptly, which her browser can
      show and her SPA can retry.

      A moment later the container is back, and the same name with the same cookie serves the same
      path. That beat is what gives the other two their meaning: a door that answered 502 to
      everything would have passed them both.

      And a name that was configured to point at something which has never been there at all fails
      earlier, at connect, before a byte is written. It is deliberately NOT the same as a name
      nobody claims — that one is a 404, because nobody ever said it was a service. This one is
      somebody's, and the somebody is missing.

      Both refusals are answers. Neither of them is silence, and silence is the failure this
      service's two bounded pools and its acquisition timeout exist to make impossible.
      """)
  @UserflowRunsAfter(ForwardAuthBootstrapIT.class)
  void anUnreachableServiceIsAnsweredRatherThanWaitedFor(Interactions story) {
    StoryUpstream projects = StoryUpstream.attach(StoryTarget.PROJECTS);
    long askedBefore = StoryNetwork.introspections();

    // --- (1) the container is being replaced: it takes the request and then goes away.
    NetworkCapture.actor(DURING_A_REPLACEMENT);
    projects.dropping(true);
    EdgeClient.Answer duringTheOutage;
    long elapsedMillis;
    try {
      long start = System.nanoTime();
      duringTheOutage =
          StoryEdge.client()
              .get(
                  StoryTarget.PROJECTS_HOST,
                  StoryTarget.OUTAGE_PATH,
                  StoryTarget.session(StoryTarget.OUTAGE_SESSION));
      elapsedMillis = (System.nanoTime() - start) / 1_000_000;
    } finally {
      projects.dropping(false);
    }
    assertEquals(
        UPSTREAM_GONE,
        duringTheOutage.status(),
        "an upstream that went away mid-exchange is a 502 to the caller, never a hang");
    assertTrue(
        elapsedMillis < 15_000,
        "the caller waited "
            + elapsedMillis
            + "ms, which is long enough to be a bound rather than an answer");
    // The far side is what proves the request ARRIVED and then died, rather than never having been
    // sent. The recording is written before the connection is closed, for exactly this beat.
    StoryUpstream.Request dropped =
        projects.recordedRequests().stream()
            .filter(request -> StoryTarget.OUTAGE_PATH.equals(request.path()))
            .findFirst()
            .orElseThrow();
    assertEquals(
        StoryUpstream.DROPPED,
        dropped.status(),
        "the service recorded the request and then answered nothing at all");
    assertEquals(
        StoryTarget.SESSION_USER,
        dropped.header(EdgeHeaders.USER),
        "and it arrived fully formed — the identity was asserted before the hop failed, which is"
            + " what makes this an upstream failure rather than a gate failure");
    story
        .note(
            "the request reached the container and the connection went away with no status on the"
                + " wire — and she is answered a 502 anyway. Both ends of that are on the diagram:"
                + " one arrow in, one arrow out labelled `dropped`, and no status where a status"
                + " would be")
        .as("a-service-that-goes-away-mid-request-is-still-answered");

    // --- (2) it comes back. The beat that makes the other two mean something.
    NetworkCapture.actor(AFTERWARDS);
    EdgeClient.Answer afterwards =
        StoryEdge.client()
            .get(
                StoryTarget.PROJECTS_HOST,
                StoryTarget.OUTAGE_PATH,
                StoryTarget.session(StoryTarget.OUTAGE_SESSION));
    assertEquals(200, afterwards.status(), "the outage must cost the route nothing");
    assertTrue(afterwards.body().contains(StoryTarget.PROJECTS), afterwards.body());
    assertEquals(
        2,
        projects.requestsTo(StoryTarget.OUTAGE_PATH),
        "the same path, twice: once into the dark and once into a service that was back");
    story
        .note(
            "the same name, the same cookie and the same path a moment later: 200. A front door"
                + " that answered 502 to everything would have passed the beat before this one, so"
                + " this is the one that gives it its meaning")
        .as("and-it-is-still-routing-when-the-service-returns");

    // --- (3) a name that was configured to point at something that has never been there. It fails
    // at CONNECT, one step earlier, and it is deliberately not the 404 an unclaimed name gets.
    NetworkCapture.actor(ON_A_MISCONFIGURED_NAME);
    EdgeClient.Answer nowhere =
        StoryEdge.client()
            .get(
                StoryTarget.OFFLINE_HOST,
                StoryTarget.OUTAGE_PATH,
                StoryTarget.session(StoryTarget.OUTAGE_SESSION));
    assertEquals(
        UPSTREAM_GONE,
        nowhere.status(),
        "a configured name whose service is not there is a 502 — a name NOBODY configured is the"
            + " 404, and telling those two apart is the difference between a typo and an outage");
    story
        .note(
            "a configured name pointing at an address nothing listens on fails at connect, before a"
                + " byte is written — and it is a 502 rather than the 404 an unclaimed name gets,"
                + " because this name IS somebody's and the somebody is missing")
        .as("a-configured-name-with-nothing-behind-it-is-a-502-not-a-404");

    assertEquals(
        askedBefore + 1,
        StoryNetwork.introspections(),
        "one cookie, one ask — an upstream failure is not a reason to re-authenticate anybody");
  }

  @AfterAll
  static void theReportIsComplete() {
    ReportAssertions.assertComplete(StoryTarget.CATEGORY, SLUG, UserflowReport.PASSED);

    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        DURING_A_REPLACEMENT,
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.OUTAGE_PATH, StoryTarget.PROJECTS_HOST, UPSTREAM_GONE));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        AFTERWARDS,
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.OUTAGE_PATH, StoryTarget.PROJECTS_HOST, 200));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        ON_A_MISCONFIGURED_NAME,
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.OUTAGE_PATH, StoryTarget.OFFLINE_HOST, UPSTREAM_GONE));
    // The outgoing half, and the label of the first one is the point of this whole story: a WORD
    // where every other label carries a status, because no status was ever on the wire.
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        StoryTarget.SERVICE,
        StoryTarget.PROJECTS,
        StoryTarget.proxied(StoryTarget.OUTAGE_PATH, StoryUpstream.DROPPED));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        StoryTarget.SERVICE,
        StoryTarget.PROJECTS,
        StoryTarget.proxied(StoryTarget.OUTAGE_PATH, 200));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        StoryTarget.SERVICE,
        StoryTarget.IDP,
        StoryTarget.proxied("POST", StoryTarget.INTROSPECT, 200));
    // Three in, two out, one ask — and the missing third departure is the name that was never
    // reached at all, which is the connect-time failure read straight off the diagram.
    ReportAssertions.assertEdgeCount(StoryTarget.CATEGORY, SLUG, 6);
    ReportAssertions.assertOnlyEdgesFrom(
        StoryTarget.CATEGORY,
        SLUG,
        List.of(DURING_A_REPLACEMENT, AFTERWARDS, ON_A_MISCONFIGURED_NAME, StoryTarget.SERVICE));
    ReportAssertions.assertNotLeaked(StoryTarget.CATEGORY, SLUG, StoryTarget.OUTAGE_SESSION);

    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, SLUG, "a-service-that-goes-away-mid-request-is-still-answered");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, SLUG, "and-it-is-still-routing-when-the-service-returns");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, SLUG, "a-configured-name-with-nothing-behind-it-is-a-502-not-a-404");
  }
}
