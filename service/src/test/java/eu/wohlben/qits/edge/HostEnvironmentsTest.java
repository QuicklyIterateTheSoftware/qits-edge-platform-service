package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.edge.HostEnvironments.Reading;
import eu.wohlben.qits.edge.HostEnvironments.Route;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A Host name to a position in the grammar, which is the edge's whole routing decision. Plain
 * JUnit: this is the piece worth pinning without booting an application, and it is where every
 * shape a real Host header arrives in belongs.
 *
 * <p>The readings are read RIGHT TO LEFT — {@code <app>[.<env>].<project>.<domain>} — so every test
 * below states the domain, and a name's meaning comes from where a label sits rather than from
 * which configured set it belongs to.
 */
class HostEnvironmentsTest {

  /** The estate every test here reads against: two environments, three configured app names. */
  private static final HostEnvironments EDGE =
      HostEnvironments.of(
          List.of("prod", "dev"), "prod", List.of("registry", "githost", "editor"), "wohlben.eu");

  /**
   * The projection, exactly as {@code EdgeProjects.projects()} serves it: slug to whether that
   * project has a tier of environments under it.
   *
   * <p>{@code qits} is the platform's own project and has none — it is deployed once — and {@code
   * someproject} is an ordinary project that does.
   */
  private static final Map<String, Boolean> PROJECTS = projects();

  private static Map<String, Boolean> projects() {
    LinkedHashMap<String, Boolean> projects = new LinkedHashMap<>();
    projects.put("qits", false);
    projects.put("someproject", true);
    return projects;
  }

  // --- the table, both cases ---------------------------------------------------------------------

  @Test
  void theProjectDoorIsTheLabelNextToTheDomain() {
    // Either kind of project: a door is a door whether or not environments hang under it.
    assertEquals(Route.projectDoor("prod", "qits"), EDGE.route("qits.wohlben.eu", PROJECTS));
    assertEquals(
        Route.projectDoor("prod", "someproject"), EDGE.route("someproject.wohlben.eu", PROJECTS));
    assertTrue(EDGE.route("qits.wohlben.eu", PROJECTS).toDoor());
    assertFalse(EDGE.route("qits.wohlben.eu", PROJECTS).toApp());
  }

  @Test
  void anEnvLessProjectPutsItsAppsStraightUnderItsOwnLabel() {
    // `qits` supports no environments, so the label in front of it is an APPLICATION and not an
    // environment — and that application is served in the default environment, because a project
    // deployed once is deployed once.
    assertEquals(
        Route.app("prod", "editor", "qits"), EDGE.route("editor.qits.wohlben.eu", PROJECTS));
    assertEquals(
        Route.app("prod", "registry", "qits"), EDGE.route("registry.qits.wohlben.eu", PROJECTS));
    // `projects` is not a configured app name here, so it is the label the deployment projection is
    // asked about — the same position, a different answer.
    assertEquals(
        Route.unknownApp("prod", "projects", "qits"),
        EDGE.route("projects.qits.wohlben.eu", PROJECTS));
  }

  @Test
  void anEnvSupportingProjectPutsAnEnvironmentDoorUnderItsOwnLabel() {
    assertEquals(
        Route.environmentDoor("dev", "someproject"),
        EDGE.route("dev.someproject.wohlben.eu", PROJECTS));
    assertEquals(
        Route.environmentDoor("prod", "someproject"),
        EDGE.route("prod.someproject.wohlben.eu", PROJECTS));
    assertTrue(EDGE.route("dev.someproject.wohlben.eu", PROJECTS).toDoor());
  }

  @Test
  void anEnvSupportingProjectPutsItsAppsUnderAnEnvironment() {
    assertEquals(
        Route.app("dev", "registry", "someproject"),
        EDGE.route("registry.dev.someproject.wohlben.eu", PROJECTS));
    assertEquals(
        Route.app("prod", "registry", "someproject"),
        EDGE.route("registry.prod.someproject.wohlben.eu", PROJECTS));
    // The environment comes from the NAME, so everything downstream — the audience, the upstream —
    // resolves against dev rather than against the default.
    assertEquals(
        "dev",
        EDGE.route("ci.dev.someproject.wohlben.eu", PROJECTS).environment(),
        "from the name");
    assertEquals(
        Route.unknownApp("dev", "ci", "someproject"),
        EDGE.route("ci.dev.someproject.wohlben.eu", PROJECTS));
  }

  @Test
  void theSameLabelMeansDifferentThingsInDifferentProjects() {
    // The whole point of reading the env label off the PROJECTION rather than off the environment
    // list: `dev` in front of an env-less project is an application label, and in front of an
    // env-supporting one it is that project's dev door.
    assertEquals(
        Route.unknownApp("prod", "dev", "qits"), EDGE.route("dev.qits.wohlben.eu", PROJECTS));
    assertEquals(
        Route.environmentDoor("dev", "someproject"),
        EDGE.route("dev.someproject.wohlben.eu", PROJECTS));
  }

  // --- the 404s ---------------------------------------------------------------------------------

  @Test
  void anUnknownProjectLabelIsFourOhFour() {
    // At every depth the label can sit at, because it is always read at the same position.
    for (String host :
        List.of(
            "nosuchproject.wohlben.eu",
            "dev.nosuchproject.wohlben.eu",
            "editor.dev.nosuchproject.wohlben.eu")) {
      Route route = EDGE.route(host, PROJECTS);
      assertEquals(Reading.UNKNOWN_PROJECT, route.reading(), host);
      assertEquals("nosuchproject", route.project(), host);
      assertFalse(route.toApp(), host);
      assertFalse(route.toDoor(), host);
    }
  }

  @Test
  void aNameWithNoProjectLabelIsAnOrdinaryFourOhFour() {
    // The old grammar's spellings, every one of them. There is no short-form refusal any more: a
    // name missing its project label is a name with a label missing, and the label that IS next to
    // the domain is simply not a project.
    for (String host : List.of("dev.wohlben.eu", "registry.dev.wohlben.eu", "editor.wohlben.eu")) {
      assertEquals(Reading.UNKNOWN_PROJECT, EDGE.route(host, PROJECTS).reading(), host);
    }
    assertEquals("dev", EDGE.route("registry.dev.wohlben.eu", PROJECTS).project());
  }

  @Test
  void anUnknownProjectLabelIsEchoedOnlyWhenItIsALabel() {
    // It came off the wire and the answer prints it, so a label that is not one is carried as null
    // rather than spliced into a sentence.
    assertNull(EDGE.route("..wohlben.eu", PROJECTS).project());
    assertEquals(Reading.UNKNOWN_PROJECT, EDGE.route("..wohlben.eu", PROJECTS).reading());
  }

  @Test
  void anEnvironmentTheProjectDoesNotHaveIsUnreadable() {
    assertEquals(
        Route.unreadable("prod", "someproject"),
        EDGE.route("registry.staging.someproject.wohlben.eu", PROJECTS));
    // And an env-less project has NO environment tier at all, so nothing fits at that depth.
    assertEquals(
        Route.unreadable("prod", "qits"), EDGE.route("editor.prod.qits.wohlben.eu", PROJECTS));
  }

  @Test
  void aNameDeeperThanTheGrammarIsUnreadable() {
    assertEquals(
        Route.unreadable("prod", null),
        EDGE.route("a.b.c.someproject.wohlben.eu", PROJECTS),
        "four labels inside the domain is one more than the grammar has");
  }

  // --- the apex and the names that carry no position ---------------------------------------------

  @Test
  void theApexIsReadPositionally() {
    // No rescue by the caller any more: the domain is stated, so the apex is simply the name that
    // IS it. Every spelling a client may send of it.
    assertEquals(Route.apex("prod"), EDGE.route("wohlben.eu", PROJECTS));
    assertEquals(Route.apex("prod"), EDGE.route("WOHLBEN.EU", PROJECTS));
    assertEquals(Route.apex("prod"), EDGE.route("wohlben.eu.", PROJECTS));
    assertEquals(Route.apex("prod"), EDGE.route("  wohlben.eu:8443  ", PROJECTS));
    assertTrue(EDGE.route("wohlben.eu", PROJECTS).toDoor());
  }

  @Test
  void aNameOutsideTheDomainIsReadAsAMachineNameRatherThanAnsweredAsADoor() {
    // It used to be the apex reading — a door, which serves nothing — and that took the platform's
    // own machine vhosts down with it, because they are docker aliases of this container and are
    // deliberately not under the public domain. A name outside the domain is now
    // `<app>[.<env>].<machine-suffix>`: this edge's configured application set is the only thing it
    // can reach, and a leftmost label nothing configures is a 404 rather than a door.
    assertEquals(
        Route.app("dev", "registry", null), EDGE.route("registry.dev.example.com", PROJECTS));
    assertEquals(Reading.UNKNOWN_MACHINE_NAME, EDGE.route("example.com", PROJECTS).reading());
    // One label carries no app label at all, so those two stay doors — see below.
    assertEquals(Route.apex("prod"), EDGE.route("qits-platform-edge", PROJECTS));
    assertEquals(Route.apex("prod"), EDGE.route("localhost", PROJECTS));
  }

  @Test
  void anAddressLiteralAndAMissingHostAreTheSameCase() {
    // How the platform is reached before DNS exists: a bootstrap curling the host's own port.
    assertEquals(Route.apex("prod"), EDGE.route("127.0.0.1:8080", PROJECTS));
    assertEquals(Route.apex("prod"), EDGE.route("[::1]:8080", PROJECTS));
    assertEquals(Route.apex("prod"), EDGE.route("::1", PROJECTS));
    assertEquals(Route.apex("prod"), EDGE.route(null, PROJECTS));
    assertEquals(Route.apex("prod"), EDGE.route("", PROJECTS));
    assertEquals(Route.apex("prod"), EDGE.route("   ", PROJECTS));
  }

  @Test
  void anAddressIsNeverSplitIntoLabels() {
    // A label may legally be all digits, so an address that fell through to the positional reading
    // would be split against a domain it cannot be inside.
    HostEnvironments numeric =
        HostEnvironments.of(List.of("prod", "127"), "prod", List.of("0"), "0.1");
    assertEquals(Route.apex("prod"), numeric.route("127.0.0.1", PROJECTS));
  }

  // --- a one-label domain, which is the local case -----------------------------------------------

  @Test
  void aLocalCloneStatesLocalhostAndReadsTheSameWay() {
    HostEnvironments local =
        HostEnvironments.of(List.of("prod", "dev"), "prod", List.of("registry"), "localhost");
    assertEquals(Route.apex("prod"), local.route("localhost:8080", PROJECTS));
    assertEquals(Route.projectDoor("prod", "qits"), local.route("qits.localhost:8080", PROJECTS));
    assertEquals(
        Route.app("prod", "registry", "qits"),
        local.route("registry.qits.localhost:8080", PROJECTS));
    assertEquals(
        Route.app("dev", "registry", "someproject"),
        local.route("registry.dev.someproject.localhost:8080", PROJECTS));
  }

  // --- the wire's own spellings ------------------------------------------------------------------

  @Test
  void aHostHeaderArrivesInAnyCaseWithAnyPortAndAnyRootDot() {
    assertEquals(
        Route.app("dev", "editor", "someproject"),
        EDGE.route("  EDITOR.Dev.SomeProject.Wohlben.EU.:8080  ", PROJECTS));
    assertEquals(
        Route.projectDoor("prod", "someproject"),
        EDGE.route("SOMEPROJECT.wohlben.eu:443.", PROJECTS));
  }

  @Test
  void aMultiLabelDomainIsJustAsStatable() {
    HostEnvironments coUk =
        HostEnvironments.of(List.of("prod", "dev"), "prod", List.of("registry"), "example.co.uk");
    assertEquals(Route.apex("prod"), coUk.route("example.co.uk", PROJECTS));
    assertEquals(
        Route.app("dev", "registry", "someproject"),
        coUk.route("registry.dev.someproject.example.co.uk", PROJECTS));
    // The domain is a VALUE, so nothing about it is guessed: `co.uk` is inside it, not a project.
    assertEquals(
        Reading.UNKNOWN_PROJECT, coUk.route("registry.dev.example.co.uk", PROJECTS).reading());
  }

  // --- the security property ---------------------------------------------------------------------

  @Test
  void anAppLabelNothingServesIsCarriedRatherThanFallingThrough() {
    // It is NOT a fall-through: an app position is a name the edge authenticates, so a label no
    // configuration claims is handed to the caller to join the deployment projection on — and to
    // answer 404 when that claims it either.
    Route route = EDGE.route("mirror.dev.someproject.wohlben.eu", PROJECTS);
    assertEquals(Reading.UNKNOWN_APP, route.reading());
    assertEquals("mirror", route.unknownApp());
    assertNull(route.app());
    assertFalse(route.toApp(), "so nothing downstream treats it as a configured vhost");
    assertEquals("dev", route.environment(), "and the projection is asked about dev's mirror");
  }

  @Test
  void anAppLabelIsEchoedOnlyWhenItIsALabel() {
    assertNull(EDGE.route(".dev.someproject.wohlben.eu", PROJECTS).unknownApp());
    assertEquals(
        Reading.UNKNOWN_APP, EDGE.route(".dev.someproject.wohlben.eu", PROJECTS).reading());
  }

  @Test
  void noProjectsAtAllIsEveryNameButTheApex() {
    // A cold projection. The project label is mandatory, so nothing below the apex reads as
    // anything else — which is exactly what the catch-up barrier holds back.
    assertEquals(Route.apex("prod"), EDGE.route("wohlben.eu"));
    assertEquals(Reading.UNKNOWN_PROJECT, EDGE.route("qits.wohlben.eu").reading());
    assertEquals(Reading.UNKNOWN_PROJECT, EDGE.route("editor.qits.wohlben.eu").reading());
  }

  // --- what configuration refuses ----------------------------------------------------------------

  @Test
  void anEnvironmentNameThatCannotBeALabelIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> HostEnvironments.of(List.of("prod env"), "prod env", "wohlben.eu"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HostEnvironments.of(List.of("prod.eu"), "prod.eu", "wohlben.eu"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HostEnvironments.of(List.of("-prod"), "-prod", "wohlben.eu"));
  }

  @Test
  void anEmptyEnvironmentListIsRefused() {
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> HostEnvironments.of(List.of(), "prod", "wohlben.eu"))
            .getMessage()
            .contains("qits.edge.environments"));
    assertThrows(
        IllegalArgumentException.class,
        () -> HostEnvironments.of(List.of("  ", ""), "prod", "wohlben.eu"));
  }

  @Test
  void aDefaultOutsideTheListIsRefused() {
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> HostEnvironments.of(List.of("prod"), "staging", "wohlben.eu"))
            .getMessage()
            .contains("qits.edge.default-environment"));
  }

  @Test
  void anAppNameThatCannotBeALabelIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> HostEnvironments.of(List.of("prod"), "prod", List.of("my registry"), "wohlben.eu"));
  }

  @Test
  void anAppMayNowBeSpelledLikeAnEnvironment() {
    // The collision the tie-breaks existed for. Positionally there is none: an app label is only
    // ever read in front of a project or an environment, never at the environment's own position.
    HostEnvironments colliding =
        HostEnvironments.of(List.of("prod", "dev"), "prod", List.of("dev"), "wohlben.eu");
    assertEquals(
        Route.app("prod", "dev", "qits"), colliding.route("dev.qits.wohlben.eu", PROJECTS));
    assertEquals(
        Route.environmentDoor("dev", "someproject"),
        colliding.route("dev.someproject.wohlben.eu", PROJECTS));
  }

  @Test
  void aDomainThatIsNotADnsNameIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> HostEnvironments.of(List.of("prod"), "prod", "wohlben eu"));
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> HostEnvironments.of(List.of("prod"), "prod", "  "))
            .getMessage()
            .contains("the edge domain is not set"));
  }

  @Test
  void theDomainIsNormalisedOnce() {
    HostEnvironments stated =
        HostEnvironments.of(List.of("prod"), "prod", List.of("registry"), "  Wohlben.EU.  ");
    assertEquals("wohlben.eu", stated.domain());
    assertEquals(Route.apex("prod"), stated.route("wohlben.eu", PROJECTS));
  }

  // --- the reserved `landing` label
  // ---------------------------------------------------------------

  /** The environments in which something published the reserved label. Both, or neither. */
  private static final Set<String> EVERYWHERE = Set.of("prod", "dev");

  private static final Set<String> NOWHERE = Set.of();

  @Test
  void anEnvLessProjectsOwnNameIsServedByItsLandingDeployment() {
    // The door is the front of the product: `qits` is deployed once, so its landing IS this name.
    // It is carried as the label the deployment projection is asked about — the same position an
    // unconfigured app label lands on, resolved by the same join.
    assertEquals(
        Route.unknownApp("prod", "landing", "qits"),
        EDGE.route("qits.wohlben.eu", PROJECTS, EVERYWHERE));
    assertFalse(EDGE.route("qits.wohlben.eu", PROJECTS, EVERYWHERE).toDoor());
  }

  @Test
  void anEnvSupportingProjectsEnvironmentDoorIsServedByThatEnvironmentsLanding() {
    // One landing per tier, like every other application: the dev name is served by dev's.
    assertEquals(
        Route.unknownApp("dev", "landing", "someproject"),
        EDGE.route("dev.someproject.wohlben.eu", PROJECTS, EVERYWHERE));
    assertEquals(
        Route.unknownApp("prod", "landing", "someproject"),
        EDGE.route("prod.someproject.wohlben.eu", PROJECTS, EVERYWHERE));
    // And only where one is published. An environment whose landing is not deployed is the door it
    // always was, which is what makes this a join rather than a rule.
    assertEquals(
        Route.environmentDoor("dev", "someproject"),
        EDGE.route("dev.someproject.wohlben.eu", PROJECTS, Set.of("prod")));
  }

  @Test
  void anEnvSupportingProjectsBareNameIsADoorOntoTheDefaultEnvironmentsLanding() {
    // It cannot serve one itself: there are as many landings as environments and this name states
    // none. So it stays a door, and the door's `GET /` goes to the DEFAULT environment's.
    assertEquals(
        Route.projectLandingDoor("prod", "someproject"),
        EDGE.route("someproject.wohlben.eu", PROJECTS, EVERYWHERE));
    assertTrue(EDGE.route("someproject.wohlben.eu", PROJECTS, EVERYWHERE).toDoor());
    assertFalse(EDGE.route("someproject.wohlben.eu", PROJECTS, EVERYWHERE).toApp());
    // The DEFAULT environment's, and no other: a landing deployed only in dev leaves the bare name
    // with nothing of this project to send anybody to, so the built-in door redirect stays.
    assertEquals(
        Route.projectDoor("prod", "someproject"),
        EDGE.route("someproject.wohlben.eu", PROJECTS, Set.of("dev")));
  }

  @Test
  void aProjectWithNoLandingPublisherKeepsTheBuiltInDoor() {
    assertEquals(
        Route.projectDoor("prod", "qits"), EDGE.route("qits.wohlben.eu", PROJECTS, NOWHERE));
    assertEquals(
        Route.projectDoor("prod", "someproject"),
        EDGE.route("someproject.wohlben.eu", PROJECTS, NOWHERE));
    assertEquals(
        Route.environmentDoor("dev", "someproject"),
        EDGE.route("dev.someproject.wohlben.eu", PROJECTS, NOWHERE));
    // The two-argument reading is that case spelled once: nothing published, so nothing is claimed.
    assertEquals(
        EDGE.route("someproject.wohlben.eu", PROJECTS),
        EDGE.route("someproject.wohlben.eu", PROJECTS, NOWHERE));
  }

  @Test
  void theReservedLabelIsNeverASecondAddressForTheDoorItServes() {
    // `landing` means this project's root, and the root has an address already. At every app
    // position, in either kind of project, and whether or not anything published it — the label is
    // reserved by the grammar rather than by who happens to hold it.
    for (Set<String> published : List.of(EVERYWHERE, NOWHERE)) {
      assertEquals(
          Route.reservedLabel("prod", "qits"),
          EDGE.route("landing.qits.wohlben.eu", PROJECTS, published));
      assertEquals(
          Route.reservedLabel("dev", "someproject"),
          EDGE.route("landing.dev.someproject.wohlben.eu", PROJECTS, published));
      Route route = EDGE.route("landing.qits.wohlben.eu", PROJECTS, published);
      assertFalse(route.toApp());
      assertFalse(route.toDoor());
      assertNull(route.app());
      assertNull(route.unknownApp(), "it is not offered to the projection either");
    }
  }

  @Test
  void theReservedLabelIsReadAtAnAppPositionOnly() {
    // A project may be CALLED landing — that is a different position, and positions do not collide.
    Map<String, Boolean> projects = projects();
    projects.put("landing", true);
    assertEquals(
        Route.projectDoor("prod", "landing"), EDGE.route("landing.wohlben.eu", projects, NOWHERE));
    assertEquals(
        Route.environmentDoor("dev", "landing"),
        EDGE.route("dev.landing.wohlben.eu", projects, NOWHERE));
  }

  // --- machine names: the platform's own aliases, outside the stated domain ----------------------

  /**
   * The live estate's own application set, against the live stated domain. The four machine vhosts
   * below are all configured entries of it — {@code registry}, {@code mirror} and {@code githost} —
   * because that is what a machine name is read against.
   */
  private static final HostEnvironments MACHINE =
      HostEnvironments.of(
          List.of("prod", "dev"),
          "prod",
          List.of("registry", "mirror", "githost", "editor"),
          "wohlben.eu");

  @Test
  void theFourMachineVhostsTheBootstrapRendersReachTheirApplications() {
    // Named one by one, because these four ARE the platform's own build and deploy path: every
    // image
    // reference is `registry.dev.localhost:8080/qits/<app>:<ver>`, every maven build resolves
    // through `mirror.dev.localhost:8080`, and a container clones from `githost.dev.localhost:8080`
    // or, over the oauth2 transport that turns git's Basic into a Bearer,
    // `githost.dev.internal:8080`.
    // qits-bootstrap-cli's ComposeTemplate renders them as docker network aliases of this
    // container,
    // so none of them is under the public domain — and when a name outside the domain was answered
    // as
    // the apex, all four answered 404 at once and nothing on the platform could build or deploy.
    assertEquals(
        Route.app("dev", "registry", null), MACHINE.route("registry.dev.localhost:8080", PROJECTS));
    assertEquals(
        Route.app("dev", "mirror", null), MACHINE.route("mirror.dev.localhost:8080", PROJECTS));
    assertEquals(
        Route.app("dev", "githost", null), MACHINE.route("githost.dev.localhost:8080", PROJECTS));
    assertEquals(
        Route.app("dev", "githost", null), MACHINE.route("githost.dev.internal:8080", PROJECTS));
    // Every one of them a routed application vhost, gated like any other — and inside no project,
    // so
    // nothing composes a public application address out of one.
    for (String host :
        List.of(
            "registry.dev.localhost:8080",
            "mirror.dev.localhost:8080",
            "githost.dev.localhost:8080",
            "githost.dev.internal:8080")) {
      assertTrue(MACHINE.route(host, PROJECTS).toApp(), host);
      assertFalse(MACHINE.route(host, PROJECTS).toDoor(), host);
      assertNull(MACHINE.route(host, PROJECTS).project(), host);
    }
  }

  @Test
  void aMachineNameStatesItsEnvironmentOrTakesTheDefault() {
    // The env label is what keeps the tiers apart, exactly as it does under the domain.
    assertEquals(
        Route.app("prod", "registry", null),
        MACHINE.route("registry.prod.localhost:8080", PROJECTS));
    // With no env label the second label IS the suffix, and the application is served in the
    // default
    // environment — which is right for a platform service deployed once, and is the same default an
    // env-less project's applications get.
    assertEquals(
        Route.app("prod", "mirror", null), MACHINE.route("mirror.localhost:8080", PROJECTS));
    assertEquals(Route.app("prod", "githost", null), MACHINE.route("githost.internal", PROJECTS));
    // A suffix spelled like an environment is still the suffix: an env label is read only where
    // something follows it, so `mirror.dev` is the mirror on a host called `dev`, not dev's mirror.
    assertEquals(Route.app("prod", "mirror", null), MACHINE.route("mirror.dev", PROJECTS));
    // The suffix itself is never enumerated — any alias somebody gives this container works.
    assertEquals(
        Route.app("dev", "registry", null),
        MACHINE.route("registry.dev.qits-platform-edge.some.alias", PROJECTS));
  }

  @Test
  void aMachineNameWhoseLeftmostLabelIsNoApplicationIsAFourOhFour() {
    // Not a door. A door serves nothing and says so, which is the answer that hid this whole class
    // of failure; a machine name that reaches nothing is a name that is wrong, and the 404 names
    // it.
    for (String host : List.of("ci.dev.localhost:8080", "nosuchapp.localhost", "example.com")) {
      Route route = MACHINE.route(host, PROJECTS);
      assertEquals(Reading.UNKNOWN_MACHINE_NAME, route.reading(), host);
      assertFalse(route.toDoor(), host);
      assertFalse(route.toApp(), host);
      assertNull(route.app(), host);
      assertNull(route.project(), host);
    }
    assertEquals(
        "ci",
        MACHINE.route("ci.dev.localhost:8080", PROJECTS).unknownApp(),
        "carried so the 404 can name what it looked for");
    assertNull(
        MACHINE.route(".dev.localhost", PROJECTS).unknownApp(),
        "and laundered, because it came off the wire");
  }

  @Test
  void theReservedLandingLabelIsNoMachineNameEither() {
    // `landing` means a project's root. A name outside the domain is inside no project, so there is
    // no root for it to mean — and it is a 404 rather than a door, whoever published the label.
    for (String host : List.of("landing.localhost", "landing.dev.localhost", "landing.internal")) {
      Route route = MACHINE.route(host, PROJECTS, EVERYWHERE);
      assertEquals(Reading.UNKNOWN_MACHINE_NAME, route.reading(), host);
      assertFalse(route.toDoor(), host);
      assertFalse(route.toApp(), host);
    }
  }

  @Test
  void aSingleLabelMachineNameIsADoorLikeTheApex() {
    // This container's own service alias, and a name a client shortened to one label. There is no
    // app label in either — a machine name is `<app>` in front of a suffix and this is only the
    // suffix — so there is nothing to route and the door is the honest answer.
    for (String host : List.of("localhost", "internal", "qits-platform-edge", "registry")) {
      assertEquals(Route.apex("prod"), MACHINE.route(host, PROJECTS), host);
      assertTrue(MACHINE.route(host, PROJECTS).toDoor(), host);
    }
  }

  @Test
  void theApexAnAddressAndAMissingHostStayDoorsBesideTheMachineReading() {
    // Load-bearing and unchanged: this is how the platform is reached before DNS exists, so a
    // bootstrap curling the host's own port must never be read as an application name.
    assertEquals(Route.apex("prod"), MACHINE.route("wohlben.eu", PROJECTS));
    assertEquals(Route.apex("prod"), MACHINE.route("127.0.0.1:8080", PROJECTS));
    assertEquals(Route.apex("prod"), MACHINE.route("[::1]:8080", PROJECTS));
    assertEquals(Route.apex("prod"), MACHINE.route("::1", PROJECTS));
    assertEquals(Route.apex("prod"), MACHINE.route(null, PROJECTS));
    assertEquals(Route.apex("prod"), MACHINE.route("", PROJECTS));
    assertEquals(Route.apex("prod"), MACHINE.route("   ", PROJECTS));
    // And an address literal is refused the machine reading even when its own labels are spelled
    // like a configured application and an environment: a label may legally be all digits, so the
    // literal check has to come first or `127.0.0.1` routes.
    HostEnvironments numeric =
        HostEnvironments.of(List.of("prod", "0"), "prod", List.of("127"), "wohlben.eu");
    assertEquals(Route.apex("prod"), numeric.route("127.0.0.1", PROJECTS));
    assertEquals(Route.apex("prod"), numeric.route("127.0.0.1:8080", PROJECTS));
  }

  @Test
  void theMachineReadingReachesNothingUnderTheStatedDomain() {
    // The separate reading is for names OUTSIDE the domain, and that is the whole of its reach.
    // Under the domain every tie-break the positional grammar retired stays retired: the project
    // label is mandatory even where the leftmost label is a configured application spelled exactly
    // as
    // a machine vhost is, so `registry.dev.wohlben.eu` is still the 404 it became.
    assertEquals(
        Reading.UNKNOWN_PROJECT, MACHINE.route("registry.dev.wohlben.eu", PROJECTS).reading());
    assertEquals("dev", MACHINE.route("registry.dev.wohlben.eu", PROJECTS).project());
    assertEquals(
        Route.app("dev", "registry", "someproject"),
        MACHINE.route("registry.dev.someproject.wohlben.eu", PROJECTS));
    assertEquals(Route.projectDoor("prod", "qits"), MACHINE.route("qits.wohlben.eu", PROJECTS));
    assertEquals(Route.apex("prod"), MACHINE.route("wohlben.eu", PROJECTS));
    assertEquals(
        Route.unreadable("prod", "someproject"),
        MACHINE.route("registry.staging.someproject.wohlben.eu", PROJECTS));
    assertEquals(
        Route.reservedLabel("prod", "qits"),
        MACHINE.route("landing.qits.wohlben.eu", PROJECTS, EVERYWHERE));
  }
}
