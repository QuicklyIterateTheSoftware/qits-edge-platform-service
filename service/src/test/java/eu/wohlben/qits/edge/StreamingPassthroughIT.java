package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>The two planes that are not a request and a response</b> — a connection held open in both
 * directions, and an answer written over time — and they are the reason this service has the shape
 * it has.
 *
 * <p>There is no REST layer here and no JAX-RS: a framework that read a body would buffer and
 * re-encode every one of these. {@code vertx-http-proxy} streams instead, and an upgrade is spliced
 * by {@code EdgeWebSocketUpgrade}, the edge's OWN path. Every interactive terminal on the platform,
 * every SSE channel, every {@code git clone} and every OCI layer push travels one of these two.
 *
 * <p><b>The socket half is the sharpest forward-auth claim this repository can make, and it belongs
 * here rather than with the ordinary strip story.</b> An upgrade never reaches the interceptor
 * chain — {@code EdgeWebSocketUpgrade} rebuilds the handshake by hand and forwards the INBOUND
 * request's header map — so it is a second code path with its own way of losing the strip, and it
 * is the path a forged {@code X-Qits-User} used to cross this process on. Proving it means a real
 * upgrade, through a launched artifact, to a service that records what its handshake carried; a
 * canned-JSON stand-in cannot speak the protocol at all, which is one of the four reasons {@link
 * StoryUpstream} exists.
 *
 * <p><b>It also pins a KNOWN GAP rather than papering over it.</b> The upgrade path drops the
 * client's {@code Host} — the outbound client fills it in from the socket it opened — so an
 * upstream reads the name the browser typed from {@code X-Forwarded-Host} and not from {@code
 * Host}. That is asserted below in both directions: the forwarded header is right, and the {@code
 * Host} header is NOT the vhost. It costs nothing today because a handshake's {@code Host} is a
 * protocol formality rather than something a service routes on, and it is written into README.md as
 * a known gap. A story that quietly asserted only the half that works would be how it stopped being
 * known.
 *
 * <p><b>The streaming half cannot be proved by a body.</b> A proxy that buffered delivers exactly
 * the same bytes; only WHEN the first of them arrives tells the two apart, so the assertion is a
 * clock and the stand-in writes its answer in two halves with a gap between them.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class StreamingPassthroughIT {

  static final String TERMINAL_SLUG =
      "an-interactive-terminal-crosses-the-edge-and-it-crosses-it-stripped";

  static final String STREAM_SLUG =
      "a-long-answer-arrives-as-it-is-produced-not-when-it-is-finished";

  static final String AT_A_TERMINAL = "an operator at a terminal";

  static final String WATCHING_A_LOG = "an operator watching a log";

  @BeforeAll
  static void tapTheFarSide() {
    StoryNetwork.install();
  }

  @AfterAll
  static void closeClient() {
    StoryEdge.close();
  }

  @UserStory(
      value = "An interactive terminal crosses the edge, and it crosses it stripped",
      category = StoryTarget.CATEGORY)
  @UserStoryDescription(
      """
      A terminal is one dialled connection carrying a stream in both directions, with no request
      boundary anywhere inside it. It is how an operator gets a shell in a container, and it is the
      one plane on this platform where "the front door asserts who you are" has a second
      implementation.

      An upgrade does not go through the reverse proxy at all. It takes a path of this process's
      own — the built-in one assumed an unread request, threw mid-upgrade after the upstream had
      already accepted it, and leaked a pool slot per attempt until the whole origin hung — and
      that path rebuilds the handshake by hand from the inbound headers. Which means the strip has
      to happen before it, on the inbound map, or it does not happen at all. It used to not happen
      at all: an upgrade was how a forged `X-Qits-User` reached the hop behind this one.

      So the operator opens a terminal with a real session and the same four forged headers as
      every other story here. The socket comes up, the service pushes a frame down it, and the
      service's recording of the HANDSHAKE — which is the only place this is visible — shows the
      identity idp vouched for and none of her forgeries.

      One thing it also shows is a gap, stated rather than hidden: the upgrade path drops the
      client's `Host`, so the upstream reads the name she typed from `X-Forwarded-Host` and finds a
      loopback address in `Host`. It costs nothing today, because a handshake's `Host` is a
      formality and nothing routes on it one hop further in. It is asserted both ways round here so
      that it stays a known gap rather than becoming a surprise.
      """)
  @UserflowRunsAfter(ForwardAuthBootstrapIT.class)
  @Order(1)
  void aTerminalIsSplicedThroughWithTheVouchedForIdentity(Interactions story) {
    StoryUpstream projects = StoryUpstream.attach(StoryTarget.PROJECTS);
    long askedBefore = StoryNetwork.introspections();

    NetworkCapture.actor(AT_A_TERMINAL);
    String frame =
        StoryEdge.client()
            .handshake(
                StoryTarget.PROJECTS_HOST,
                StoryTarget.TERMINAL_PATH,
                StoryTarget.forgedWithSession(StoryTarget.TERMINAL_SESSION));
    assertTrue(
        frame.contains("the terminal is attached"),
        "getting a frame back at all is what proves the handshake survived the hop: " + frame);
    story
        .note(
            "the upgrade crosses the edge and the two sockets are spliced — the service pushes a"
                + " frame and it arrives. Every interactive terminal on this platform is one of"
                + " these, and an upgrade takes a code path of its own through this process")
        .as("the-socket-is-spliced-and-a-frame-comes-back");

    // The whole claim, and the only place it is visible: the HANDSHAKE's own headers, recorded by
    // the service that received it.
    StoryUpstream.Request handshake = projects.onlyRequestTo(StoryTarget.TERMINAL_PATH);
    assertEquals("socket", handshake.kind(), "it arrived as an upgrade, not as a proxied GET");
    assertEquals("101", handshake.status());
    assertEquals(
        StoryTarget.SESSION_USER,
        handshake.header(EdgeHeaders.USER),
        "the identity is asserted on the upgrade path too — which it was not, once");
    assertEquals(StoryTarget.SESSION_USER_ID, handshake.header(EdgeHeaders.USER_ID));
    assertEquals(StoryTarget.SESSION_ROLES_HEADER, handshake.header(EdgeHeaders.ROLES));
    assertEquals(
        StoryUpstream.ABSENT,
        handshake.header(StoryTarget.INVENTED_HEADER),
        "and the reserved prefix is emptied here as well, not just on the proxied path");
    story
        .note(
            "the service's recording of the HANDSHAKE shows X-Qits-User: "
                + StoryTarget.SESSION_USER
                + " and none of the four forgeries. This is a second implementation of the strip —"
                + " the upgrade path never installs the interceptor chain — and it is the path a"
                + " forged identity used to cross this process on")
        .as("the-forgeries-are-gone-on-the-upgrade-path-too");

    // The forwarded description arrives; the Host header does not. Both halves asserted, because
    // the second is a documented gap and a story that pinned only the first is how it would stop
    // being documented.
    assertEquals(
        StoryTarget.PROJECTS_HOST,
        handshake.header(EdgeHeaders.HOST),
        "the name she typed reaches the service as X-Forwarded-Host");
    assertEquals("http", handshake.header(EdgeHeaders.PROTO));
    assertNotEquals(
        StoryTarget.PROJECTS_HOST,
        handshake.header("Host"),
        "…and NOT as Host: the upgrade path rebuilds the handshake with the client's own Host"
            + " dropped, so the outbound client fills it in from the socket it opened. A known gap,"
            + " asserted so it stays known");
    story
        .note(
            "the socket's original name arrives as X-Forwarded-Host and NOT as Host — the upgrade"
                + " path rebuilds the handshake with the client's own Host dropped. It costs"
                + " nothing today because a handshake's Host is a protocol formality, and it is"
                + " asserted both ways round here so it stays a known gap rather than a surprise")
        .as("the-known-gap-a-handshakes-host-is-the-sockets-own");

    assertEquals(
        askedBefore + 1,
        StoryNetwork.introspections(),
        "a socket is gated exactly like a page: one cookie, one ask, before the splice");
  }

  @UserStory(
      value = "A long answer arrives as it is produced, not when it is finished",
      category = StoryTarget.CATEGORY)
  @UserStoryDescription(
      """
      The other long-lived plane, and the one no body assertion can prove. A build log, an SSE
      channel, a `git clone`'s pack and a `docker pull`'s layers are all answers a service writes
      over seconds or minutes, and a proxy that buffered would deliver exactly the same bytes — at
      the end, all at once, after the person watching had given up.

      So the service is armed to write its answer in two halves with a real gap between them, and
      the assertion is a clock rather than a comparison: the first half has to be in the caller's
      hands well before the second was even written. That is the whole reason there is no REST
      layer in this process — a framework that read a body would have buffered this one.

      It is one HTTP exchange and it draws as one arrow, on purpose. How many pieces the bytes
      arrived in belongs to the service's own flush boundaries and not to this story; an arrow per
      chunk would document a buffer size.
      """)
  @UserflowRunsAfter(ForwardAuthBootstrapIT.class)
  @Order(2)
  void aChunkedAnswerIsNotBuffered(Interactions story) {
    StoryUpstream projects = StoryUpstream.attach(StoryTarget.PROJECTS);
    long askedBefore = StoryNetwork.introspections();

    NetworkCapture.actor(WATCHING_A_LOG);
    EdgeClient.Streamed streamed =
        StoryEdge.client().stream(
            StoryTarget.PROJECTS_HOST,
            StoryTarget.STREAM_PATH,
            StoryTarget.session(StoryTarget.STREAM_SESSION));

    assertEquals(200, streamed.status());
    assertTrue(streamed.body().contains("the channel is open"), streamed.body());
    assertTrue(streamed.body().contains("event: closed"), streamed.body());
    assertTrue(
        streamed.firstChunkMillis() < StoryProfile.STREAM_GAP_MILLIS,
        "the first half arrived after "
            + streamed.firstChunkMillis()
            + "ms, which is not before the service wrote the second at "
            + StoryProfile.STREAM_GAP_MILLIS
            + "ms — the answer was buffered somewhere in the middle");
    story
        .note(
            "the first half of the answer is in the caller's hands before the service has written"
                + " the second — which is the only way to tell a streaming proxy from a buffering"
                + " one, because both deliver the same bytes. A build log, an SSE channel and a"
                + " `git clone` all depend on this")
        .as("the-first-half-arrives-before-the-second-is-written");

    assertEquals(
        "200",
        projects.onlyRequestTo(StoryTarget.STREAM_PATH).status(),
        "one exchange, answered by the service — the pieces are its flush boundaries, not a story's");
    assertEquals(
        askedBefore + 1,
        StoryNetwork.introspections(),
        "a session is introspected before the first byte, not per chunk");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // --- the terminal ----------------------------------------------------------------------------
    ReportAssertions.assertComplete(StoryTarget.CATEGORY, TERMINAL_SLUG, UserflowReport.PASSED);
    // The dial, from the operator. A word where a status would be: from a client, a socket that
    // came
    // up has no status to report — the 101 belongs to the far side's recording.
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        TERMINAL_SLUG,
        "socket",
        AT_A_TERMINAL,
        StoryTarget.SERVICE,
        "GET "
            + StoryTarget.TERMINAL_LABEL_PATH
            + " on "
            + StoryTarget.PROJECTS_HOST
            + " -> "
            + EdgeClient.ATTACHED);
    // The same dial one hop on, and this one DOES carry a status, because the upstream answered it.
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        TERMINAL_SLUG,
        "socket",
        StoryTarget.SERVICE,
        StoryTarget.PROJECTS,
        StoryTarget.proxied(StoryTarget.TERMINAL_LABEL_PATH, 101));
    // The frame, in the direction it was PUSHED — which is the vocabulary's own rule and the reason
    // this arrow points the other way from the two above it.
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        TERMINAL_SLUG,
        "event",
        StoryTarget.SERVICE,
        AT_A_TERMINAL,
        EdgeClient.FRAME);
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        TERMINAL_SLUG,
        "http",
        StoryTarget.SERVICE,
        StoryTarget.IDP,
        StoryTarget.proxied("POST", StoryTarget.INTROSPECT, 200));
    ReportAssertions.assertEdgeCount(StoryTarget.CATEGORY, TERMINAL_SLUG, 4);
    ReportAssertions.assertOnlyEdgesFrom(
        StoryTarget.CATEGORY, TERMINAL_SLUG, List.of(AT_A_TERMINAL, StoryTarget.SERVICE));
    ReportAssertions.assertNoEdgesTo(StoryTarget.CATEGORY, TERMINAL_SLUG, StoryTarget.MIRROR);
    ReportAssertions.assertNotLeaked(
        StoryTarget.CATEGORY, TERMINAL_SLUG, StoryTarget.TERMINAL_SESSION);
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, TERMINAL_SLUG, "the-socket-is-spliced-and-a-frame-comes-back");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, TERMINAL_SLUG, "the-forgeries-are-gone-on-the-upgrade-path-too");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, TERMINAL_SLUG, "the-known-gap-a-handshakes-host-is-the-sockets-own");

    // --- the stream ------------------------------------------------------------------------------
    ReportAssertions.assertComplete(StoryTarget.CATEGORY, STREAM_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        STREAM_SLUG,
        "http",
        WATCHING_A_LOG,
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.STREAM_PATH, StoryTarget.PROJECTS_HOST, 200));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        STREAM_SLUG,
        "http",
        StoryTarget.SERVICE,
        StoryTarget.PROJECTS,
        StoryTarget.proxied(StoryTarget.STREAM_PATH, 200));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        STREAM_SLUG,
        "http",
        StoryTarget.SERVICE,
        StoryTarget.IDP,
        StoryTarget.proxied("POST", StoryTarget.INTROSPECT, 200));
    // Three: one exchange in, one out, one ask. An arrow per chunk would be five and would document
    // the service's flush boundaries rather than the dependency.
    ReportAssertions.assertEdgeCount(StoryTarget.CATEGORY, STREAM_SLUG, 3);
    ReportAssertions.assertOnlyEdgesFrom(
        StoryTarget.CATEGORY, STREAM_SLUG, List.of(WATCHING_A_LOG, StoryTarget.SERVICE));
    ReportAssertions.assertNotLeaked(StoryTarget.CATEGORY, STREAM_SLUG, StoryTarget.STREAM_SESSION);
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, STREAM_SLUG, "the-first-half-arrives-before-the-second-is-written");
  }
}
