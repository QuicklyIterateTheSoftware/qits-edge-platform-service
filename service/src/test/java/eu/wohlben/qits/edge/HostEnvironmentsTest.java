package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Host name to environment, the edge's whole routing decision. Plain JUnit: this is the piece worth
 * pinning without booting an application, and it is where every shape a real Host header arrives in
 * belongs.
 */
class HostEnvironmentsTest {

  private static final HostEnvironments TWO = HostEnvironments.of(List.of("prod", "dev"), "prod");

  @Test
  void anEnvironmentSubdomainNamesItsEnvironment() {
    assertEquals("dev", TWO.resolve("dev.example.com"));
    assertEquals("prod", TWO.resolve("prod.example.com"));
  }

  @Test
  void anApplicationInAnEnvironmentNamesTheEnvironment() {
    assertEquals("dev", TWO.resolve("home.dev.example.com"));
    assertEquals("prod", TWO.resolve("artifacts.prod.example.com"));
  }

  @Test
  void theApexGoesToTheDefault() {
    assertEquals("prod", TWO.resolve("example.com"));
    assertEquals("prod", TWO.resolve("example.co.uk"));
  }

  @Test
  void anUnknownEnvironmentGoesToTheDefault() {
    // The failure mode this prevents: a tier that was decommissioned, or a name someone mistyped,
    // answering with a connection error instead of the platform's own page.
    assertEquals("prod", TWO.resolve("staging.example.com"));
    assertEquals("prod", TWO.resolve("home.staging.example.com"));
  }

  @Test
  void aDomainOfAnyDepthWorksBecauseOnlyTheLeadingLabelsAreRead() {
    assertEquals("dev", TWO.resolve("dev.example.co.uk"));
    assertEquals("dev", TWO.resolve("home.dev.example.co.uk"));
    assertEquals("dev", TWO.resolve("dev.localhost"));
  }

  @Test
  void aPortSuffixIsNotPartOfTheName() {
    // A browser sends `Host: dev.example.com:8080` whenever the origin is not on the default port,
    // and a developer's every request looks like this.
    assertEquals("dev", TWO.resolve("dev.example.com:8080"));
    assertEquals("dev", TWO.resolve("home.dev.example.com:443"));
    assertEquals("prod", TWO.resolve("example.com:8080"));
  }

  @Test
  void caseAndSurroundingSpaceAndTheRootDotAreAllTolerated() {
    assertEquals("dev", TWO.resolve("DEV.Example.COM"));
    assertEquals("dev", TWO.resolve("  dev.example.com  "));
    assertEquals("dev", TWO.resolve("dev.example.com."));
    assertEquals("dev", TWO.resolve("Home.DEV.example.com:8080"));
  }

  @Test
  void localhostGoesToTheDefault() {
    assertEquals("prod", TWO.resolve("localhost"));
    assertEquals("prod", TWO.resolve("localhost:8080"));
  }

  @Test
  void anAddressLiteralGoesToTheDefault() {
    // How the platform is reached before DNS exists — a bootstrap curling the host's own port.
    assertEquals("prod", TWO.resolve("127.0.0.1"));
    assertEquals("prod", TWO.resolve("127.0.0.1:8080"));
    assertEquals("prod", TWO.resolve("[::1]"));
    assertEquals("prod", TWO.resolve("[::1]:8080"));
    assertEquals("prod", TWO.resolve("::1"));
  }

  @Test
  void aNumericEnvironmentNameCannotCaptureLoopback() {
    // The reason IPv4 is recognised by SHAPE rather than left to the label comparison: a DNS label
    // may legally be all digits, so without the check an environment called `127` would take every
    // request that arrived at the host's own address.
    HostEnvironments numeric = HostEnvironments.of(List.of("prod", "127"), "prod");
    assertEquals("prod", numeric.resolve("127.0.0.1"));
    assertEquals("127", numeric.resolve("127.example.com"));
  }

  @Test
  void aMissingHostGoesToTheDefault() {
    assertEquals("prod", TWO.resolve(null));
    assertEquals("prod", TWO.resolve(""));
    assertEquals("prod", TWO.resolve("   "));
  }

  @Test
  void theThreeLabelReadingWinsWhenAnApplicationSharesAnEnvironmentName() {
    // `dev.prod.example.com`: application `dev` in environment `prod`, not environment `dev` in a
    // domain that happens to start with `prod`. An application may be called anything; a domain
    // whose first label is an environment name is a coincidence nobody arranges.
    assertEquals("prod", TWO.resolve("dev.prod.example.com"));
    assertEquals("dev", TWO.resolve("prod.dev.example.com"));
  }

  @Test
  void aSingleEnvironmentTakesEverything() {
    HostEnvironments one = HostEnvironments.of(List.of("prod"), "prod");
    assertEquals("prod", one.resolve("anything.at.all.example.com"));
    assertEquals("prod", one.resolve("prod.example.com"));
    assertEquals(1, one.environments().size());
  }

  @Test
  void anEmptyEnvironmentListIsRefusedAtConstruction() {
    // Not a per-request 502: an edge with nothing to forward to has to say so once, at boot.
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> HostEnvironments.of(List.of(), "prod"))
            .getMessage()
            .contains("qits.edge.environments"));
    assertThrows(
        IllegalArgumentException.class, () -> HostEnvironments.of(List.of("  ", ""), "prod"));
  }

  @Test
  void aDefaultOutsideTheListIsRefusedAtConstruction() {
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> HostEnvironments.of(List.of("prod"), "staging"))
            .getMessage()
            .contains("qits.edge.default-environment"));
  }

  // --- the application label ---------------------------------------------------------------------

  private static final HostEnvironments APPS =
      HostEnvironments.of(List.of("prod", "dev"), "prod", List.of("registry", "githost"));

  @Test
  void aConfiguredApplicationLabelReachesThatApplicationInThatEnvironment() {
    assertEquals(
        new HostEnvironments.Route("dev", "registry", null), APPS.route("registry.dev.localhost"));
    assertEquals(
        new HostEnvironments.Route("prod", "registry", null),
        APPS.route("registry.prod.localhost"));
    assertEquals(
        new HostEnvironments.Route("dev", "githost", null),
        APPS.route("GITHOST.dev.example.com:8080."));
  }

  @Test
  void anEnvironmentOnlyNameStillReachesItsGateway() {
    // The whole of today's behaviour, unchanged: no app, no rejection. A name that carries no shape
    // at all — one label, an address, nothing — is the default environment's door, as it has always
    // been.
    assertEquals(new HostEnvironments.Route("dev", null, null), APPS.route("dev.localhost"));
    assertEquals(new HostEnvironments.Route("prod", null, null), APPS.route("localhost"));
    assertEquals(new HostEnvironments.Route("prod", null, null), APPS.route("127.0.0.1:8080"));
    assertEquals(new HostEnvironments.Route("prod", null, null), APPS.route(null));
  }

  @Test
  void theApexIsNotToldApartHereAndIsNotServedByThisClass() {
    // `example.com` and `nosuchapp.example.com` are the same shape read from the left, and only the
    // configured canonical origin tells them apart. So this class answers both alike — a name that
    // states no environment — and EdgeRouter, which knows the apex, is where the door is restored.
    assertFalse(APPS.route("example.com").envExplicit());
    assertEquals("prod", APPS.route("example.com").environment());
  }

  @Test
  void anUnconfiguredApplicationLabelIsUnroutable() {
    // It does NOT become the gateway's. The name is app-shaped, so it was aimed at a service, and
    // the gateway is the hop that does not authenticate those.
    HostEnvironments.Route route = APPS.route("mirror.dev.localhost");
    assertEquals("mirror", route.unknownApp());
    assertEquals("dev", route.environment(), "the environment is still readable, for the message");
    assertFalse(route.toApp());
  }

  @Test
  void anApplicationLabelWithNoEnvironmentIsNoLongerServed() {
    // FLIPPED, and deliberately. `registry.example.com` was the default environment's registry —
    // the environment label was optional there, because the apex is that environment's door. The
    // project tier ended it: a middle label cannot be read as an environment in one spelling and a
    // project in another. The long spelling is now the only spelling.
    assertEquals(
        HostEnvironments.Route.shortForm("prod", "registry", null),
        APPS.route("registry.example.com"));
    assertEquals(
        new HostEnvironments.Route("prod", "registry", null),
        APPS.route("registry.prod.example.com"));
    assertEquals(
        HostEnvironments.Route.shortForm("prod", "githost", null),
        APPS.route("GITHOST.example.co.uk:8080."));
    // The environment is still readable — it is the one the explicit spelling would carry, and the
    // answer names it.
    assertEquals("prod", APPS.route("registry.example.com").environment());
  }

  @Test
  void anEnvironmentNameStillWinsOverAnApplicationOne() {
    // Precedence is unchanged: an environment at either position is read first, so no tier can be
    // hidden by an application whose name looks like one. `staging` is neither here, so it is a
    // name that states no environment.
    assertEquals(new HostEnvironments.Route("dev", null, null), APPS.route("dev.example.com"));
    assertEquals(
        HostEnvironments.Route.shortForm("prod", "staging", null),
        APPS.route("staging.example.com"));
  }

  @Test
  void aNameWithNoEnvironmentCarriesItsLeadingLabelSoTheAnswerCanNameTheSpelling() {
    // FLIPPED: this label used to be OFFERED to the router as an application in the default
    // environment, and the router joined the deployment projection onto it. That join is gone with
    // the fall-through it served. What the label is for now is the sentence the 404 writes.
    assertEquals("ci", APPS.route("ci.example.com").unknownApp());
    assertEquals("ci", APPS.route("CI.example.co.uk:8080.").unknownApp());
    assertEquals("registry", APPS.route("registry.example.com").unknownApp());
    assertEquals("anything", APPS.route("anything.at.all.example.com").unknownApp());
    // And on the readings that ARE served it keeps its old meaning exactly: a label in front of a
    // known environment that nothing configured claims.
    assertEquals("ci", APPS.route("ci.dev.example.com").unknownApp());
    assertTrue(APPS.route("ci.dev.example.com").envExplicit());
    // A name with nothing in front of a domain has no label to name.
    assertNull(APPS.route("localhost").unknownApp());
    assertNull(APPS.route("127.0.0.1").unknownApp());
    assertNull(APPS.route(null).unknownApp());
  }

  @Test
  void anUnknownLabelWithNoEnvironmentIsNotServedEither() {
    // FLIPPED. A mistyped or decommissioned name on the apex used to reach the platform's own page.
    // It cannot any more: `ci.example.com` and `editor.acme.example.com` are the same kind of name,
    // and the second one has to fail loudly or the editor silently opens the wrong environment.
    assertFalse(APPS.route("ci.example.com").envExplicit());
    assertEquals("prod", APPS.route("ci.example.com").environment(), "for the explicit spelling");
    assertFalse(APPS.route("anything.at.all.example.com").envExplicit());
  }

  @Test
  void anEdgeWithNoApplicationsRoutesNoAppLabel() {
    assertEquals("registry", TWO.route("registry.dev.localhost").unknownApp());
  }

  @Test
  void anApplicationNameThatCannotBeADnsLabelIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> HostEnvironments.of(List.of("prod"), "prod", List.of("my registry")));
  }

  @Test
  void anApplicationMayNotShareAnEnvironmentsName() {
    // The tie-break reads the first label as an application, so an app called `dev` would swallow
    // `dev.prod.example.com` and leave the dev environment unreachable by name. Fail at boot.
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> HostEnvironments.of(List.of("prod", "dev"), "prod", List.of("dev")))
            .getMessage()
            .contains("both an environment and an application"));
  }

  // --- the project label -------------------------------------------------------------------------

  /** The live set, as {@code EdgeProjects} serves it. Two, so a slug is never the only label. */
  private static final java.util.Set<String> PROJECTS = java.util.Set.of("acme", "qits");

  /** `editor` is the application served per project; `registry` and `githost` never are. */
  private static final HostEnvironments TIERS =
      HostEnvironments.of(List.of("prod", "dev"), "prod", List.of("registry", "githost", "editor"));

  @Test
  void aProjectInFrontOfAnEnvironmentIsThatProjectsDoor() {
    assertEquals(
        HostEnvironments.Route.projectDoor("dev", "acme"),
        TIERS.route("acme.dev.example.com", PROJECTS));
    assertEquals(
        HostEnvironments.Route.projectDoor("prod", "qits"),
        TIERS.route("qits.prod.example.com", PROJECTS));
    assertTrue(TIERS.route("acme.dev.example.com", PROJECTS).toProjectDoor());
    assertFalse(TIERS.route("acme.dev.example.com", PROJECTS).toApp());
  }

  @Test
  void anApplicationInFrontOfAProjectInFrontOfAnEnvironmentIsTheFourLabelTier() {
    // The name this whole tier exists for, spelled as the platform spells it.
    assertEquals(
        new HostEnvironments.Route("dev", "editor", null, "qits", true),
        TIERS.route("editor.qits.dev.wohlben.dev", PROJECTS));
    // The environment comes from position 2, which is what makes the audience, the upstream and
    // every origin the NAMED environment's rather than the default's.
    assertEquals("dev", TIERS.route("editor.qits.dev.wohlben.dev", PROJECTS).environment());
    assertEquals("prod", TIERS.route("editor.qits.prod.wohlben.dev", PROJECTS).environment());
  }

  @Test
  void anUnconfiguredApplicationOnTheFourLabelTierIsOfferedToTheProjection() {
    // `workspaces` is nobody's configured app: it is a name a DEPLOYMENT publishes, so the label is
    // carried as unknown and the router joins the projection onto it — with the project kept, so
    // the answer either way names the whole reading.
    HostEnvironments.Route route = TIERS.route("workspaces.acme.dev.example.com", PROJECTS);
    assertEquals("workspaces", route.unknownApp());
    assertEquals("acme", route.project());
    assertEquals("dev", route.environment());
    assertTrue(route.envExplicit());
    assertFalse(route.toApp());
  }

  @Test
  void aProjectThatDoesNotExistIsJustAnotherLabel() {
    // The set is live, and a slug nobody has created is not a tier. `nosuchproject` in the middle
    // makes the name state no environment, exactly like any other unknown middle label.
    assertFalse(TIERS.route("editor.nosuchproject.dev.example.com", PROJECTS).envExplicit());
    assertEquals(
        HostEnvironments.Route.shortForm("prod", "nosuchproject", null),
        TIERS.route("nosuchproject.example.com", PROJECTS));
  }

  @Test
  void aServiceLabelBeatsAProjectAtPositionZero() {
    // Both sets are keyed by a bare label and nothing stops a project being called `registry`. The
    // configured application wins, because a vhost with an audience and anonymous reads going to a
    // door is the worse of the two failures — and the platform refuses the collision on its side
    // rather than relying on this.
    assertEquals(
        new HostEnvironments.Route("dev", "registry", null),
        TIERS.route("registry.dev.example.com", java.util.Set.of("registry")));
  }

  @Test
  void anEnvironmentBeatsAProjectAtPositionOne() {
    // The platform prevents this collision, so this only decides what happens if the two ever
    // disagree: `editor.dev.example.com` is the editor in dev, not the editor of a project called
    // `dev` in a domain that starts with one.
    assertEquals(
        new HostEnvironments.Route("dev", "editor", null),
        TIERS.route("editor.dev.example.com", java.util.Set.of("dev")));
  }

  @Test
  void theShortSpellingsOfBothProjectTiersAreRefusedAndNameTheirLabels() {
    // No redirect, by decision: a short name is a bookmark or a hard-coded string, and the label it
    // is missing is the one that decides which tier the request lands in.
    assertEquals(
        HostEnvironments.Route.shortForm("prod", "editor", "qits"),
        TIERS.route("editor.qits.wohlben.dev", PROJECTS));
    assertEquals(
        HostEnvironments.Route.shortForm("prod", null, "qits"),
        TIERS.route("qits.wohlben.dev", PROJECTS));
    // The default environment is carried on both, because it is what the explicit spelling holds.
    assertEquals("prod", TIERS.route("editor.qits.wohlben.dev", PROJECTS).environment());
  }

  @Test
  void aFourLabelNameToleratesEverythingATwoLabelOneDoes() {
    // A port, letter case, the root dot and surrounding space, at the depth a browser really sends.
    assertEquals(
        new HostEnvironments.Route("dev", "editor", null, "qits", true),
        TIERS.route("  EDITOR.Qits.DEV.wohlben.dev.:8080  ", PROJECTS));
    assertEquals(
        HostEnvironments.Route.projectDoor("dev", "acme"),
        TIERS.route("ACME.dev.example.co.uk:443.", PROJECTS));
  }

  @Test
  void withNoProjectsEveryNonProjectNameReadsExactlyAsItDoesWithThem() {
    // The regression sweep: the project set may not change the answer for a name that names no
    // project. Whatever these are, they are the same either way.
    for (String host :
        List.of(
            "dev.example.com",
            "example.com",
            "registry.dev.example.com",
            "registry.prod.example.com",
            "registry.example.com",
            "mirror.dev.example.com",
            "staging.example.com",
            "anything.at.all.example.com",
            "editor.dev.example.com",
            "localhost",
            "127.0.0.1:8080",
            "dev.localhost")) {
      assertEquals(TIERS.route(host), TIERS.route(host, PROJECTS), host);
    }
    // And the project readings are the only difference, in both directions.
    assertEquals(
        HostEnvironments.Route.shortForm("prod", "acme", null), TIERS.route("acme.example.com"));
    assertEquals(
        HostEnvironments.Route.shortForm("prod", null, "acme"),
        TIERS.route("acme.example.com", PROJECTS));
  }

  @Test
  void anEnvironmentNameThatCannotBeADnsLabelIsRefused() {
    // The name is interpolated into a host name, so a value DNS could never resolve is a
    // configuration error rather than a connection failure per request.
    assertThrows(
        IllegalArgumentException.class, () -> HostEnvironments.of(List.of("prod env"), "prod env"));
    assertThrows(
        IllegalArgumentException.class, () -> HostEnvironments.of(List.of("prod.eu"), "prod.eu"));
    assertThrows(
        IllegalArgumentException.class, () -> HostEnvironments.of(List.of("-prod"), "-prod"));
  }
}
