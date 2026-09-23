package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The origins one request's own name yields, without booting anything — the other half of {@code
 * HostEnvironmentsTest}. What is asserted here is written into a {@code Location} header and into
 * every entry of {@code /main-navigation}, so a wrong answer is a link the browser cannot follow.
 *
 * <p>The composition is the reading spelled forwards: {@code <app>[.<env>].<project>.<domain>}, and
 * the env label is there exactly when the project supports environments. Both spellings are
 * asserted for the SAME project below, because that is what the platform's own project does when a
 * {@code ProjectChanged} frame flips its flag.
 */
class EnvironmentAuthorityTest {

  private static final String DOMAIN = "example.com";

  /** Two environments and a default that is the other one, as the platform's own edge has. */
  private static final HostEnvironments HOSTS =
      HostEnvironments.of(List.of("prod", "dev"), "prod", Set.of("ci", "projects"), DOMAIN);

  /** The live projection: one project with a tier of environments, one without. */
  private static final Map<String, Boolean> PROJECTS = projects("acme", true, "qits", false);

  @Test
  void anAppOfAnEnvSupportingProjectComposesOnItsEnvironmentsDoor() {
    // `<app>.<env>.<project>.<domain>`, and the authority is everything behind the app label — so
    // one label in front of it is another application of the same project, in the same environment.
    assertEquals("http://dev.acme.example.com", of("ci.dev.acme.example.com").origin());
    assertEquals(
        "http://projects.dev.acme.example.com",
        of("ci.dev.acme.example.com").hostOrigin("projects"));
    assertEquals("acme", of("ci.dev.acme.example.com").project());
  }

  @Test
  void anAppOfAnEnvLessProjectHasNoEnvironmentLabelAtAll() {
    // The whole difference, and it is not a special case: an env-less project holds its
    // applications directly, so the innermost door is the project's own name.
    assertEquals("http://qits.example.com", of("projects.qits.example.com").origin());
    assertEquals("http://ci.qits.example.com", of("projects.qits.example.com").hostOrigin("ci"));
    assertEquals("qits", of("projects.qits.example.com").project());
  }

  @Test
  void oneProjectComposesBothShapesAccordingToItsFlag() {
    // The platform's own project, on both sides of the day its flag flips. Nothing is hard-coded
    // for it: the same slug composes `<app>.<env>.qits.<domain>` while it supports environments and
    // `<app>.qits.<domain>` afterwards, from the projection alone.
    Map<String, Boolean> tiered = projects("qits", true);
    assertEquals(
        "http://projects.dev.qits.example.com",
        EnvironmentAuthority.of(
                "ci.dev.qits.example.com", null, "http", HOSTS, tiered, "example.com")
            .hostOrigin("projects"));
    Map<String, Boolean> flat = projects("qits", false);
    assertEquals(
        "http://projects.qits.example.com",
        EnvironmentAuthority.of("ci.qits.example.com", null, "http", HOSTS, flat, "example.com")
            .hostOrigin("projects"));
    // A project the projection carries with no answer at all is one that HAS environments — the
    // same compatibility rule HostEnvironments reads it by, so the two cannot disagree.
    Map<String, Boolean> unstated = new LinkedHashMap<>();
    unstated.put("qits", null);
    assertEquals(
        "http://dev.qits.example.com",
        EnvironmentAuthority.of(
                "ci.dev.qits.example.com", null, "http", HOSTS, unstated, "example.com")
            .origin());
  }

  @Test
  void anEnvironmentDoorIsTheAuthorityItself() {
    assertEquals("http://dev.acme.example.com", of("dev.acme.example.com").origin());
    assertEquals("http://ci.dev.acme.example.com", of("dev.acme.example.com").hostOrigin("ci"));
  }

  @Test
  void aProjectDoorComposesTheDefaultEnvironmentWhenTheProjectHasThem() {
    // The name states a project and no environment, so the environment is the default — the same
    // answer HostEnvironments gives that name, which is what keeps the door's redirect on a name
    // the router serves.
    assertEquals("http://prod.acme.example.com", of("acme.example.com").origin());
    assertEquals(
        "http://projects.prod.acme.example.com", of("acme.example.com").hostOrigin("projects"));
    // And with no environments there is nothing to default: the door IS the authority.
    assertEquals("http://qits.example.com", of("qits.example.com").origin());
  }

  @Test
  void theNavigationDocumentIsTheSameOnEveryNameOfOnePlace() {
    // Which is what lets a shell be served from any application of a project: the place is the
    // same place whichever of its services was asked.
    for (String host :
        List.of(
            "dev.acme.example.com", "ci.dev.acme.example.com", "nosuchapp.dev.acme.example.com")) {
      assertEquals("http://dev.acme.example.com", of(host).origin(), host);
      assertEquals("http://ci.dev.acme.example.com", of(host).hostOrigin("ci"), host);
    }
  }

  @Test
  void aNameInsideNoProjectComposesNoApplicationNameAtAll() {
    // The apex, an address, a name outside the domain and a name whose project label names no
    // project. Every application address carries a project label now, so there is no name to
    // compose — and the honest answer is none, not one that 404s a hop later. `origin` stays the
    // canonical origin, which is a door like the name that was asked.
    for (String host :
        List.of(
            "example.com",
            "example.com.",
            "127.0.0.1",
            "[::1]:8080",
            "somewhere-else.test",
            "dev.nosuchproject.example.com")) {
      assertEquals("http://example.com", of(host).origin(), host);
      assertNull(of(host).hostOrigin("projects"), host);
      assertNull(of(host).project(), host);
    }
    assertEquals("http://example.com", of(null).origin(), "no Host header at all");
    assertNull(of(null).hostOrigin("projects"));
  }

  @Test
  void theCanonicalOriginIsReadByTheSameGrammarAndIsWhatGivesTheApexAFrontDoor() {
    // The one name a deployment states about itself. Stated as the platform project's own door, it
    // is what the apex composes on — which is how `https://wohlben.eu` can still send a visitor to
    // a name that exists. Stated as the bare apex it names no project, and nothing composes.
    assertEquals(
        "http://projects.qits.example.com",
        EnvironmentAuthority.of("example.com", null, "http", HOSTS, PROJECTS, "qits.example.com")
            .hostOrigin("projects"),
        "an env-less project's door");
    assertEquals(
        "http://projects.prod.acme.example.com",
        EnvironmentAuthority.of("example.com", null, "http", HOSTS, PROJECTS, "acme.example.com")
            .hostOrigin("projects"),
        "an env-supporting project's door, in the default environment");
    assertEquals(
        "http://projects.dev.acme.example.com",
        EnvironmentAuthority.of("127.0.0.1", null, "http", HOSTS, PROJECTS, "dev.acme.example.com")
            .hostOrigin("projects"),
        "a canonical origin that states an environment keeps it");
    assertNull(
        EnvironmentAuthority.of("example.com", null, "http", HOSTS, PROJECTS, "example.com")
            .hostOrigin("projects"),
        "the bare apex names no project, so the apex composes nothing");
  }

  @Test
  void thePortIsPartOfTheAnswer() {
    // A developer's whole platform is one port, so an origin without it names nothing.
    assertEquals("http://dev.acme.localhost:8080", local("ci.dev.acme.localhost:8080").origin());
    assertEquals(
        "http://ci.dev.acme.localhost:8080", local("dev.acme.localhost:8080").hostOrigin("ci"));
    assertEquals("http://qits.localhost:8080", local("projects.qits.localhost:8080").origin());
    // Reached on the bare apex, or on nothing at all, it is the canonical origin's own port.
    assertEquals("http://localhost:8080", local("localhost").origin());
    assertEquals("http://localhost:8080", local(null).origin());
  }

  @Test
  void aTrailingDotAndLetterCaseAreTolerated() {
    assertEquals("http://dev.acme.example.com", of("CI.Dev.ACME.Example.COM.").origin());
  }

  @Test
  void aNameThatEndsAtTheLabelItWasReadForFallsBackInsteadOfThrowing() {
    // A Host header is caller input, and these are names that stop before the domain does. There is
    // no position to read, so there is no origin to derive: they are the apex reading, and the
    // answer is the one an unusable name already gets. They used to be a substring past the end of
    // the string — an unauthenticated 500 on `/main-navigation` and on the door's own redirect.
    for (String host : List.of("prod", "ci.dev", "acme.dev", "ci.dev.acme", "example", "com")) {
      assertEquals("http://example.com", of(host).origin(), host);
      assertNull(of(host).hostOrigin("ci"), host);
    }
  }

  @Test
  void aNameTheGrammarCannotDescribeStillNamesItsProjectsDoor() {
    // Too many labels names no project at all. An environment the project does not have names one,
    // so its 404 can still say where to start — the default environment of the project it named.
    assertEquals("http://example.com", of("a.b.c.acme.example.com").origin());
    assertNull(of("a.b.c.acme.example.com").hostOrigin("projects"));
    assertEquals("http://prod.acme.example.com", of("ci.staging.acme.example.com").origin());
  }

  @Test
  void theSchemeIsTheOutermostHopsWhenThereIsOne() {
    // A TLS terminator in front of the edge is the only hop that knows the answer, and the header
    // is a list with the outermost hop first.
    assertEquals(
        "https://dev.acme.example.com",
        EnvironmentAuthority.of("ci.dev.acme.example.com", "https", "http", HOSTS, PROJECTS, DOMAIN)
            .origin());
    assertEquals(
        "https://ci.qits.example.com",
        EnvironmentAuthority.of(
                "projects.qits.example.com", "https, http", "http", HOSTS, PROJECTS, DOMAIN)
            .hostOrigin("ci"));
    assertEquals(
        "http://dev.acme.example.com",
        EnvironmentAuthority.of(
                "ci.dev.acme.example.com", "gopher", "http", HOSTS, PROJECTS, DOMAIN)
            .origin(),
        "a value that is not a scheme is not believed");
    assertEquals(
        "https://dev.acme.example.com",
        EnvironmentAuthority.of("ci.dev.acme.example.com", null, "HTTPS", HOSTS, PROJECTS, DOMAIN)
            .origin(),
        "and with nothing forwarded it is the scheme this process was reached over");
  }

  private static EnvironmentAuthority of(String host) {
    return EnvironmentAuthority.of(host, null, "http", HOSTS, PROJECTS, DOMAIN);
  }

  /** A developer's platform: one environment, and it is the default, on a single-label domain. */
  private static EnvironmentAuthority local(String host) {
    return EnvironmentAuthority.of(
        host,
        null,
        "http",
        HostEnvironments.of(List.of("dev"), "dev", Set.of("ci", "projects"), "localhost"),
        PROJECTS,
        "localhost:8080");
  }

  private static Map<String, Boolean> projects(Object... slugsAndFlags) {
    Map<String, Boolean> projects = new LinkedHashMap<>();
    for (int i = 0; i < slugsAndFlags.length; i += 2) {
      projects.put((String) slugsAndFlags[i], (Boolean) slugsAndFlags[i + 1]);
    }
    return projects;
  }
}
