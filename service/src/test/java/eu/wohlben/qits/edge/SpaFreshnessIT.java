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
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>A released SPA is on screen at once, and this is the hop that decides whether it is.</b>
 *
 * <p>Every service on the platform serves its own single-page application, and Quarkus puts one
 * blanket header on every static resource: {@code Cache-Control: public, immutable, max-age=86400}.
 * On a file whose NAME changes with its content that is exactly right — a new build writes a new
 * name, so the old one can be kept forever. On everything else it is a day-long freeze, and the
 * worst thing it freezes is the SPA document itself, which is the file that decides which build of
 * the application a returning browser runs.
 *
 * <p><b>This is a defect this repository has already had once.</b> qits-gateway carried the rewrite
 * and was the only hop that did; when it was retired in favour of this process, the rewrite was not
 * carried across, and every platform SPA went back to shipping an immutable document. What that
 * looks like from a chair is the thing worth remembering: a release goes green, deploys, and does
 * not appear. Nothing on screen says why. It comes right roughly a day later, which reads as
 * flakiness rather than as a cache. {@code EdgeCacheControl} is the restored fix and this story is
 * what a reader can point at.
 *
 * <p><b>Why it is safe to correct a header at all.</b> The edge does not overrule decisions: it
 * rewrites the ONE value that is known to be nobody's — the untouched framework default — and
 * leaves every header a handler chose. So this story serves all three shapes rather than only the
 * interesting one: a document and an unhashed asset carrying the default, a content-hashed bundle
 * carrying the same default, and a route whose own handler said {@code no-store}. Two are corrected
 * and two are not, and which two is the whole of the rule.
 *
 * <p><b>Every one of the four requests reaches the service.</b> That is worth stating because the
 * evidence for a header rewrite could otherwise look like a refusal: the far side's recording shows
 * four arrivals, so the difference between what the upstream sent and what the browser received
 * happened in the middle.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class SpaFreshnessIT {

  static final String SLUG =
      "a-released-spa-is-on-screen-at-once-the-edge-unfreezes-the-document-that-names-the-bundles";

  static final String RETURNING = "a returning browser";

  @BeforeAll
  static void tapTheFarSide() {
    StoryNetwork.install();
  }

  @AfterAll
  static void closeClient() {
    StoryEdge.close();
  }

  @UserStory(
      value =
          "A released SPA is on screen at once: the edge unfreezes the document that names the bundles",
      category = StoryTarget.CATEGORY)
  @UserStoryDescription(
      """
      A person comes back to a page she had open yesterday. Overnight the service behind it was
      released — a green build, deployed, live. Whether she sees it is decided here, by one header
      on one file.

      Every qits service serves its SPA through Quarkus, which marks every static resource
      `public, immutable, max-age=86400`. For a bundle whose filename carries a hash of its own
      contents that is the right answer and a valuable one: the name changes when the content does,
      so a browser may keep the old one forever and never ask again. For the `index.html` that
      NAMES those bundles it is a disaster, because that file's name never changes — so a browser
      that has it keeps running yesterday's application for a day, and nothing anywhere says why.
      It comes right on its own eventually, which is what makes it read as flakiness instead of as
      a cache.

      So the front door corrects exactly that: the document and the un-hashed assets go out as
      `no-cache`, which still revalidates and still costs one round trip for a 304, while the
      content-hashed bundle keeps its year. And a route whose own handler chose `no-store` keeps
      that, because a header somebody decided is a decision and this process does not overrule
      decisions — it only corrects the one value that is known to be nobody's.

      All four requests reach the service. The difference between what it sent and what she
      received happened in the middle, which is the only place it could have.
      """)
  @UserflowRunsAfter(ForwardAuthBootstrapIT.class)
  void theDocumentIsRevalidatedAndTheHashedBundleIsNot(Interactions story) {
    StoryUpstream projects = StoryUpstream.attach(StoryTarget.PROJECTS);
    long askedBefore = StoryNetwork.introspections();
    NetworkCapture.actor(RETURNING);

    // --- (1) the document. The file that decides which build of the application she runs.
    EdgeClient.Answer document =
        StoryEdge.client()
            .get(
                StoryTarget.PROJECTS_HOST,
                StoryTarget.SPA_DOCUMENT,
                StoryTarget.session(StoryTarget.RETURNING_SESSION));
    assertEquals(200, document.status());
    assertTrue(document.body().contains("<!doctype html>"), document.body());
    assertEquals(
        EdgeCacheControl.REVALIDATE,
        document.headers().get("cache-control"),
        "the SPA document must never be frozen: it is what names the bundles");

    // --- (2) the bundle it names. Its name IS the version, so a year is correct.
    EdgeClient.Answer bundle =
        StoryEdge.client()
            .get(
                StoryTarget.PROJECTS_HOST,
                StoryTarget.SPA_BUNDLE,
                StoryTarget.session(StoryTarget.RETURNING_SESSION));
    assertEquals(200, bundle.status());
    assertEquals(
        EdgeCacheControl.STATIC_DEFAULT,
        bundle.headers().get("cache-control"),
        "a content-hashed file may be kept forever — a new build writes a new name");
    story
        .note(
            "the document comes back revalidating and the hash-named bundle it points at keeps its"
                + " year: the name changing with the content is the ENTIRE justification for"
                + " `immutable`, so the edge corrects it exactly where the name does not")
        .as("the-document-revalidates-and-the-hashed-bundle-does-not");

    // --- (3) an asset whose name does not change with its content. Same default, same correction:
    // the rule is about the NAME, not about the file being a document.
    EdgeClient.Answer logo =
        StoryEdge.client()
            .get(
                StoryTarget.PROJECTS_HOST,
                StoryTarget.SPA_LOGO,
                StoryTarget.session(StoryTarget.RETURNING_SESSION));
    assertEquals(200, logo.status());
    assertEquals(
        EdgeCacheControl.REVALIDATE,
        logo.headers().get("cache-control"),
        "a logo, a favicon and an i18n file are frozen by the same default and are corrected by the"
            + " same rule");

    // --- (4) a header the upstream CHOSE. Untouched, and a blanket rewrite would have weakened it.
    EdgeClient.Answer priv =
        StoryEdge.client()
            .get(
                StoryTarget.PROJECTS_HOST,
                StoryTarget.PRIVATE_READ,
                StoryTarget.session(StoryTarget.RETURNING_SESSION));
    assertEquals(200, priv.status());
    assertEquals(
        "no-store",
        priv.headers().get("cache-control"),
        "a decision a handler made is a decision — and a blanket rewrite to no-cache would have"
            + " WEAKENED this one");
    story
        .note(
            "only the untouched framework default is corrected. A route that said `no-store` keeps"
                + " it — the edge does not overrule decisions, it corrects the one value that is"
                + " known to be nobody's")
        .as("a-header-the-upstream-chose-is-left-exactly-as-it-was");

    // --- all four arrived. The rewrite is a response header, not a refusal, and this is what says
    // so: the far side served every one of them.
    assertEquals(
        "200", projects.onlyRequestTo(StoryTarget.SPA_DOCUMENT).status(), "the document arrived");
    assertEquals("200", projects.onlyRequestTo(StoryTarget.SPA_BUNDLE).status());
    assertEquals("200", projects.onlyRequestTo(StoryTarget.SPA_LOGO).status());
    assertEquals("200", projects.onlyRequestTo(StoryTarget.PRIVATE_READ).status());
    story
        .note(
            "all four reached qits-projects and all four were answered there — so the difference"
                + " between what the service sent and what the browser received was made in the"
                + " middle, which is the only place a proxy can make one")
        .as("every-one-of-the-four-reached-the-service");

    assertEquals(
        askedBefore + 1,
        StoryNetwork.introspections(),
        "four reads behind one cookie are one introspection — a page load is a burst, and a gate"
            + " that asked per request would put idp on the path of every asset");
  }

  @AfterAll
  static void theReportIsComplete() {
    ReportAssertions.assertComplete(StoryTarget.CATEGORY, SLUG, UserflowReport.PASSED);

    for (String path :
        List.of(
            StoryTarget.SPA_DOCUMENT,
            StoryTarget.SPA_BUNDLE,
            StoryTarget.SPA_LOGO,
            StoryTarget.PRIVATE_READ)) {
      ReportAssertions.assertEdge(
          StoryTarget.CATEGORY,
          SLUG,
          "http",
          RETURNING,
          StoryTarget.SERVICE,
          StoryTarget.arriving(path, StoryTarget.PROJECTS_HOST, 200));
      ReportAssertions.assertEdge(
          StoryTarget.CATEGORY,
          SLUG,
          "http",
          StoryTarget.SERVICE,
          StoryTarget.PROJECTS,
          StoryTarget.proxied(path, 200));
    }
    ReportAssertions.assertEdge(
        StoryTarget.CATEGORY,
        SLUG,
        "http",
        StoryTarget.SERVICE,
        StoryTarget.IDP,
        StoryTarget.proxied("POST", StoryTarget.INTROSPECT, 200));
    // Four in, four out, one ask. A page load that put idp on the path of every asset would be
    // three edges more, and only this count would notice.
    ReportAssertions.assertEdgeCount(StoryTarget.CATEGORY, SLUG, 9);
    ReportAssertions.assertOnlyEdgesFrom(
        StoryTarget.CATEGORY, SLUG, List.of(RETURNING, StoryTarget.SERVICE));
    ReportAssertions.assertNoEdgesTo(StoryTarget.CATEGORY, SLUG, StoryTarget.DOCS);
    ReportAssertions.assertNotLeaked(StoryTarget.CATEGORY, SLUG, StoryTarget.RETURNING_SESSION);

    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, SLUG, "the-document-revalidates-and-the-hashed-bundle-does-not");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, SLUG, "a-header-the-upstream-chose-is-left-exactly-as-it-was");
    ReportAssertions.assertStepId(
        StoryTarget.CATEGORY, SLUG, "every-one-of-the-four-reached-the-service");
  }
}
