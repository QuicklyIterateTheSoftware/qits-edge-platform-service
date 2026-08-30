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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>The path on which the edge asserts NOTHING — and therefore the path on which the strip matters
 * most.</b>
 *
 * <p>{@code ForwardAuthBootstrapIT} proves the strip where there is something to put back: a
 * validated session's identity replaces whatever a client claimed. That is the reassuring half, and
 * it is not the half that broke. Two paths out of this process assert no identity at all — a
 * MACHINE credential, whose identity is inside its own token, and a read a deployment opened
 * through {@code qits.edge.auth.anonymous-read-apps}, where nobody has vouched for anybody. On
 * those paths there is no trusted value to write, so the temptation is to do nothing; and doing
 * nothing means a client-supplied {@code X-Qits-User} travels to a service that believes it
 * unconditionally.
 *
 * <p><b>That was the shape of the defect, and it is why the strip moved.</b> It used to live on the
 * session branch, which is exactly where it is least needed. It is now unconditional, in {@code
 * EdgeRouter.proxy}, at the one point every request leaves this process by — {@code
 * EdgeHeaders.applyIdentity(headers, null)} empties the reserved namespace and writes nothing back.
 * This story is that half, asserted from the far side, on the vhost where a caller really does
 * arrive with no credential at all.
 *
 * <p><b>And the cookie, which is the other direction of the same idea.</b> A session cookie set on
 * the parent domain is offered by the browser to every sibling name, by design — including the ones
 * no browser session belongs on. A registry, a mirror and a git host do not consume a person
 * session, so forwarding it there would turn a browser credential into a bearer any of those
 * services could see. {@code EdgeHeaders.stripCookie} removes that one pair and preserves every
 * other, and the only place that is checkable is a recording of what arrived.
 *
 * <p><b>The third beat is what keeps the exemption honest.</b> It is per app LABEL, so the same
 * request on a gated name is refused — the open read is one service's, not a path opened across the
 * environment.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class AnonymousReadIT {

  static final String SLUG = "an-open-read-is-still-not-a-forged-identity";

  static final String A_MACHINE = "a machine with nothing to offer";

  static final String A_PAGES_FETCH = "a logged-in page fetching from the mirror";

  static final String THE_SAME_MACHINE = "the same machine, on a gated name";

  @BeforeAll
  static void tapTheFarSide() {
    StoryNetwork.install();
  }

  @AfterAll
  static void closeClient() {
    StoryEdge.close();
  }

  @UserStory(value = "An open read is still not a forged identity", category = StoryTarget.CATEGORY)
  @UserStoryDescription(
      """
      Some names have to serve a caller who has nothing at all. Pulling a base image on a fresh
      node, cloning a public repository, fetching a dependency — each of them happens before there
      is anything to hold a credential, so a deployment can open the READS on a name by naming its
      app label. Everything that changes what the platform will run still needs a token.

      Which puts the reserved header namespace in an awkward spot. On an open read nobody has
      vouched for anybody, so the edge has no identity to write — and the tempting thing to do with
      no identity is nothing at all. Then a stranger's `X-Qits-User: admin` travels, unaltered, to
      a service that believes it without checking. That was the shape of it, and it is why the
      strip is now unconditional: it happens at the single point every request leaves this process
      by, whether or not there is anything to put in its place.

      So a machine asks the mirror for a manifest with no credential of any kind, stamping the
      three headers and a fourth nobody has invented yet. It is served — and the mirror's own
      recording shows the namespace arriving empty.

      Then a page that IS logged in fetches from the same name with a token of its own. The browser
      attaches the session cookie because the mirror is a sibling of the name she logged in on;
      that is browser design and nothing can stop it happening. What can be stopped is the cookie
      reaching a service that has no business with a person's credential — and the recording shows
      it gone, with her unrelated cookies untouched.

      Last, the same open read on a gated name. Refused. The exemption is one app label's, not a
      path opened across an environment.
      """)
  @UserflowRunsAfter(ForwardAuthBootstrapIT.class)
  void anOpenReadAssertsNothingAndCarriesNothing(Interactions story) {
    StoryUpstream mirror = StoryUpstream.attach(StoryTarget.MIRROR);
    StoryUpstream projects = StoryUpstream.attach(StoryTarget.PROJECTS);
    long askedBefore = StoryNetwork.introspections();

    // --- (1) nothing at all, plus four forgeries.
    NetworkCapture.actor(A_MACHINE);
    EdgeClient.Answer pulled =
        StoryEdge.client()
            .get(
                StoryTarget.MIRROR_HOST,
                StoryTarget.MIRROR_READ,
                StoryTarget.forged(Map.of("Accept", "application/json")));
    assertEquals(200, pulled.status(), "a read a deployment opened is served to anyone");

    StoryUpstream.Request arrived = mirror.onlyRequestTo(StoryTarget.MIRROR_READ);
    assertEquals(
        StoryUpstream.ABSENT,
        arrived.header(EdgeHeaders.USER),
        "an anonymous read has no identity, so the service must be told none — not the caller's");
    assertEquals(StoryUpstream.ABSENT, arrived.header(EdgeHeaders.USER_ID));
    assertEquals(StoryUpstream.ABSENT, arrived.header(EdgeHeaders.ROLES));
    assertEquals(
        StoryUpstream.ABSENT,
        arrived.header(StoryTarget.INVENTED_HEADER),
        "the rule is the `X-Qits-` prefix here too, on a path with nothing to write back");
    story
        .note(
            "the read is served with no credential at all — and the service is told nothing about"
                + " who asked, because nobody vouched for anybody. The strip cannot be conditional"
                + " on there being an identity to replace the forgery with: it is exactly where"
                + " there is none that a forgery would travel")
        .as("an-anonymous-read-arrives-with-the-namespace-empty");

    // --- (2) a page that is logged in, fetching from a sibling name with a token of its own. The
    // browser attaches the session cookie because the name is a sibling; nothing on the client side
    // can prevent that, so the front door is where it has to be removed.
    NetworkCapture.actor(A_PAGES_FETCH);
    Map<String, String> fromAPage = new LinkedHashMap<>(StoryTarget.forged(Map.of()));
    fromAPage.putAll(StoryTarget.session("a-session-a-sibling-name-should-never-see"));
    fromAPage.put("Authorization", "Bearer a-token-of-its-own");
    fromAPage.put("Sec-Fetch-Mode", "cors");
    EdgeClient.Answer fetched =
        StoryEdge.client().get(StoryTarget.MIRROR_HOST, StoryTarget.MIRROR_LAYER, fromAPage);
    assertEquals(200, fetched.status());

    StoryUpstream.Request atTheMirror = mirror.onlyRequestTo(StoryTarget.MIRROR_LAYER);
    assertEquals(
        "theme=dark",
        atTheMirror.header("Cookie"),
        "the person's session cookie is removed and every unrelated cookie survives exactly as the"
            + " browser sent it");
    assertEquals(
        StoryUpstream.ABSENT,
        atTheMirror.header(EdgeHeaders.USER),
        "nothing was checked on an open read, so nothing may be asserted from it either");
    assertEquals(
        "Bearer a-token-of-its-own",
        atTheMirror.header("Authorization"),
        "the caller's own credential travels untouched: its identity is in its token, one hop"
            + " further in, which is precisely why this hop asserts none");
    story
        .note(
            "a parent-domain session cookie reaches every sibling name by browser design — so it is"
                + " removed here, and only it: `theme=dark` arrives exactly as it was sent. A"
                + " registry, a mirror and a git host do not consume a person session, and"
                + " forwarding one would turn a browser credential into a service-visible bearer")
        .as("a-persons-cookie-does-not-reach-a-machine-name");

    // --- (3) the same open read on a name that is not open. The exemption is one app label's.
    NetworkCapture.actor(THE_SAME_MACHINE);
    EdgeClient.Answer refused =
        StoryEdge.client()
            .get(
                StoryTarget.PROJECTS_HOST,
                StoryTarget.MIRROR_READ,
                StoryTarget.forged(Map.of("Accept", "application/json")));
    assertEquals(401, refused.status());
    assertTrue(
        refused.body().contains("UNAUTHORIZED"),
        "a machine on a gated name gets the machine refusal: " + refused.body());
    assertEquals(
        0,
        projects.requestsTo(StoryTarget.MIRROR_READ),
        "an open read is a NAME's, so the same request on a gated name reaches nothing");
    story
        .note(
            "the same request, one name over, is refused: `anonymous-read-apps` names app labels,"
                + " so opening reads on the mirror opens nothing anywhere else — and the refused"
                + " request reached qits-projects not at all")
        .as("the-exemption-is-one-names-and-not-a-paths");

    assertEquals(
        askedBefore,
        StoryNetwork.introspections(),
        "idp was never asked: a machine credential is a machine saying who it is, and a machine has"
            + " no session to introspect");
  }

  @AfterAll
  static void theReportIsComplete() {
    ReportAssertions.assertComplete(StoryTarget.CATEGORY, SLUG, UserflowReport.PASSED);

    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        A_MACHINE,
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.MIRROR_READ, StoryTarget.MIRROR_HOST, 200));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        A_PAGES_FETCH,
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.MIRROR_LAYER, StoryTarget.MIRROR_HOST, 200));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        THE_SAME_MACHINE,
        StoryTarget.SERVICE,
        StoryTarget.arriving(StoryTarget.MIRROR_READ, StoryTarget.PROJECTS_HOST, 401));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        StoryTarget.SERVICE,
        StoryTarget.MIRROR,
        StoryTarget.proxied(StoryTarget.MIRROR_READ, 200));
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        StoryTarget.SERVICE,
        StoryTarget.MIRROR,
        StoryTarget.proxied(StoryTarget.MIRROR_LAYER, 200));
    // Three in, two out — and the missing third departure is the refusal, read straight off the
    // diagram.
    ReportAssertions.assertEdgeCount(StoryTarget.CATEGORY, SLUG, 5);
    // Both services were up and answering: the mirror answered twice in this very story.
    ReportAssertions.assertNoEdgesTo(StoryTarget.CATEGORY, SLUG, StoryTarget.PROJECTS);
    // And idp is not on the path of an anonymous read, which is what keeps a `docker pull` on a
    // fresh node from depending on the identity provider being up.
    ReportAssertions.assertNoEdgesTo(StoryTarget.CATEGORY, SLUG, StoryTarget.IDP);
    ReportAssertions.assertOnlyEdgesFrom(
        StoryTarget.CATEGORY,
        SLUG,
        List.of(A_MACHINE, A_PAGES_FETCH, THE_SAME_MACHINE, StoryTarget.SERVICE));
    ReportAssertions.assertNotLeaked(
        StoryTarget.CATEGORY, SLUG, "a-session-a-sibling-name-should-never-see");
    ReportAssertions.assertNotLeaked(StoryTarget.CATEGORY, SLUG, "a-token-of-its-own");

    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, SLUG, "an-anonymous-read-arrives-with-the-namespace-empty");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, SLUG, "a-persons-cookie-does-not-reach-a-machine-name");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, SLUG, "the-exemption-is-one-names-and-not-a-paths");
  }
}
