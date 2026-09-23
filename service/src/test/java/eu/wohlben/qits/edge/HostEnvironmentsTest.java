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
  void aNameOutsideTheDomainCarriesNoPositionAndServesNothing() {
    // The labels are somebody else's grammar, so there is nothing to read — and the answer is the
    // one a door gives: no app, no project, nothing proxied.
    for (String host :
        List.of("example.com", "registry.dev.example.com", "qits-platform-edge", "localhost")) {
      assertEquals(Route.apex("prod"), EDGE.route(host, PROJECTS), host);
    }
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
}
