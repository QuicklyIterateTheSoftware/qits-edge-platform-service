package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The origins one request's own name yields, without booting anything — the other half of {@code
 * HostEnvironmentsTest}. What is asserted here is written into a {@code Location} header and into
 * every entry of {@code /main-navigation}, so a wrong answer is a link the browser cannot follow.
 */
class EnvironmentAuthorityTest {

  private static final List<String> ENVIRONMENTS = List.of("prod", "dev");

  /** The live set, as {@code EdgeProjects} serves it — the middle label of the four-label tier. */
  private static final List<String> PROJECTS = List.of("acme", "qits");

  @Test
  void anEnvironmentsOwnNameIsTheAuthorityItself() {
    assertEquals("http://dev.example.com", of("dev.example.com").origin());
    assertEquals("http://ci.dev.example.com", of("dev.example.com").hostOrigin("ci"));
  }

  @Test
  void anApplicationsNameLosesItsFirstLabel() {
    // Which is what lets the navigation document be identical on every vhost: the environment is
    // the same place whichever of its services was asked.
    assertEquals("http://dev.example.com", of("ci.dev.example.com").origin());
    assertEquals(
        "http://registry.dev.example.com", of("ci.dev.example.com").hostOrigin("registry"));
  }

  @Test
  void theTieBreakIsTheSameAsTheRoutersOwn() {
    // `dev.prod.example.com` reads as application `dev` in environment `prod`, exactly as
    // HostEnvironments reads it, so its origins are prod's.
    assertEquals("http://prod.example.com", of("dev.prod.example.com").origin());
  }

  @Test
  void theDefaultEnvironmentKeepsItsLabelLikeEveryOther() {
    // FLIPPED. The default environment's authority used to be the apex, because the apex is its
    // door — `ci.example.com` was where its ci service was. The project tier ended that spelling:
    // with a project label in the middle, the short and long forms would need the same label read
    // two ways. So there is one authority per environment now, and it carries the label.
    assertEquals("http://prod.example.com", of("prod.example.com").origin());
    assertEquals("http://ci.prod.example.com", of("prod.example.com").hostOrigin("ci"));
    assertEquals("http://prod.example.com", of("ci.prod.example.com").origin());
    assertEquals("http://ci.prod.example.com", of("ci.prod.example.com").hostOrigin("ci"));
  }

  @Test
  void aProjectIsALabelInTheAuthorityAndTheApexIsWhatIsLeftAfterThree() {
    // The name the four-label tier is for. Without this reading the editor's own host would fall
    // through to the canonical origin and claim the DEFAULT environment — the navigation document
    // would render dev's tree against prod's origins, which is the bug this exists to prevent.
    assertEquals("http://dev.example.com", of("editor.acme.dev.example.com").origin());
    assertEquals("http://ci.dev.example.com", of("editor.acme.dev.example.com").hostOrigin("ci"));
    assertEquals("http://prod.example.com", of("editor.qits.prod.example.com").origin());
    // A project's own door is one label in front of the environment, like a service's name.
    assertEquals("http://dev.example.com", of("acme.dev.example.com").origin());
    // And a middle label nobody created is not a project, so the name says nothing and falls back.
    assertEquals("http://prod.example.com", of("editor.nosuchproject.dev.example.com").origin());
  }

  @Test
  void aProjectHostOriginIsTwoLabelsInFrontOfTheEnvironment() {
    // What `projectOrigin` in the navigation document is for: the client prefixes `editor.<slug>.`
    // onto the authority, and this is the same name written here.
    assertEquals(
        "http://editor.acme.dev.example.com",
        of("dev.example.com").projectHostOrigin("editor", "acme"));
    assertEquals(
        "http://editor.acme.prod.example.com",
        of("example.com").projectHostOrigin("editor", "acme"));
    assertEquals(
        "http://editor.acme.dev.localhost:8080",
        local("dev.localhost:8080").projectHostOrigin("editor", "acme"));
  }

  @Test
  void everyOtherEnvironmentKeepsItsLabel() {
    assertEquals("http://dev.example.com", of("dev.example.com").origin());
    assertEquals("http://ci.dev.example.com", of("ci.dev.example.com").hostOrigin("ci"));
  }

  @Test
  void thePortIsPartOfTheAnswer() {
    // A developer's whole platform is one port, so an origin without it names nothing.
    assertEquals("http://dev.localhost:8080", of("dev.localhost:8080").origin());
    assertEquals("http://ci.dev.localhost:8080", of("ci.dev.localhost:8080").hostOrigin("ci"));
  }

  @Test
  void aTrailingDotAndLetterCaseAreTolerated() {
    assertEquals("http://dev.example.com", of("DEV.Example.COM.").origin());
  }

  @Test
  void theApexAnAddressAndAnUnknownNameFallBackToTheCanonicalOrigin() {
    // None of them says which environment it is, so the answer is the configured apex with the
    // default environment in front. The apex ITSELF is still the door — it is simply not a name
    // anything derives, and a request that arrives on it is answered a name that carries a label.
    assertEquals("http://prod.example.com", of("example.com").origin());
    assertEquals("http://prod.example.com", of("staging.example.com").origin());
    assertEquals("http://prod.example.com", of("127.0.0.1").origin());
    assertEquals("http://prod.example.com", of("[::1]:8080").origin());
    assertEquals("http://prod.example.com", of(null).origin());
  }

  @Test
  void theCanonicalOriginKeepsItsOwnPortAndIsReadEitherWayItIsSpelled() {
    // A single-label apex is now the same rule rather than an exception to one: `localhost` names
    // every environment at once, and so does every other apex's authority now.
    assertEquals(
        "http://prod.localhost:8080",
        EnvironmentAuthority.of(
                null, null, "http", ENVIRONMENTS, "prod", PROJECTS, "localhost:8080")
            .origin());
    // And a canonical origin that already carries the default environment's label is read as the
    // same apex, so a deployment cannot get `prod.prod.example.com` by spelling its origin either
    // way.
    assertEquals(
        "http://prod.example.com",
        EnvironmentAuthority.of(
                "example.com", null, "http", ENVIRONMENTS, "prod", PROJECTS, "prod.example.com")
            .origin());
    assertEquals(
        "example.com", EnvironmentAuthority.apex("prod.example.com", "prod"), "either spelling");
    assertEquals("example.com", EnvironmentAuthority.apex("example.com", "prod"));
    assertEquals(
        "localhost",
        EnvironmentAuthority.apex("dev.localhost:8080", "dev"),
        "the label comes off a single-label apex too — the authority puts it straight back on");
  }

  @Test
  void aDevelopersWholePlatformIsOnePortAndOneLabelApex() {
    assertEquals("http://dev.localhost:8080", local("dev.localhost:8080").origin());
    assertEquals("http://ci.dev.localhost:8080", local("ci.dev.localhost:8080").hostOrigin("ci"));
    // Reached on the bare apex, or on nothing at all, it is the canonical origin's own port.
    assertEquals("http://dev.localhost:8080", local("localhost:8080").origin());
    assertEquals("http://dev.localhost:8080", local(null).origin());
  }

  @Test
  void aNameThatEndsAtTheLabelItWasReadForFallsBackInsteadOfThrowing() {
    // Each of these MATCHES one of the readings above and then stops: there is no apex behind the
    // label, so there is no origin to derive. They used to be a substring past the end of the
    // string — an unauthenticated 500 on `/main-navigation` and on the door's own redirect, from a
    // Host header anybody can send. The answer is the one an unusable name already got.
    assertEquals("http://prod.example.com", of("prod").origin(), "an environment and nothing else");
    assertEquals("http://prod.example.com", of("ci.dev").origin(), "$app.$env with no domain");
    assertEquals("http://prod.example.com", of("acme.dev").origin(), "$project.$env, the same");
    assertEquals(
        "http://prod.example.com",
        of("editor.acme.dev").origin(),
        "the four-label reading, three labels long");
    // The port a request carried is still the port its answer is written on, whichever arm
    // answered.
    assertEquals("http://dev.localhost:8080", local("ci.dev").origin());
    // And the readings that DO have something behind them are untouched by the guard: `dev.dev` is
    // environment `dev` under an apex spelled `dev`, which the first reading cannot serve and the
    // third one can.
    assertEquals("http://dev.dev", of("dev.dev").origin());
  }

  @Test
  void theSchemeIsTheOutermostHopsWhenThereIsOne() {
    // A TLS terminator in front of the edge is the only hop that knows the answer, and the header
    // is a list with the outermost hop first.
    assertEquals(
        "https://dev.example.com",
        EnvironmentAuthority.of(
                "dev.example.com", "https", "http", ENVIRONMENTS, "prod", PROJECTS, "example.com")
            .origin());
    assertEquals(
        "https://dev.example.com",
        EnvironmentAuthority.of(
                "dev.example.com",
                "https, http",
                "http",
                ENVIRONMENTS,
                "prod",
                PROJECTS,
                "example.com")
            .origin());
    assertEquals(
        "http://dev.example.com",
        EnvironmentAuthority.of(
                "dev.example.com", "gopher", "http", ENVIRONMENTS, "prod", PROJECTS, "example.com")
            .origin(),
        "a value that is not a scheme is not believed");
    // The scheme reaches the project tier's origins too — they are built from the same two halves.
    assertEquals(
        "https://editor.acme.dev.example.com",
        EnvironmentAuthority.of(
                "editor.acme.dev.example.com",
                "https",
                "http",
                ENVIRONMENTS,
                "prod",
                PROJECTS,
                "example.com")
            .projectHostOrigin("editor", "acme"));
  }

  private static EnvironmentAuthority of(String host) {
    return EnvironmentAuthority.of(
        host, null, "http", ENVIRONMENTS, "prod", PROJECTS, "example.com");
  }

  /** A developer's platform: one environment, and it is the default, on a single-label apex. */
  private static EnvironmentAuthority local(String host) {
    return EnvironmentAuthority.of(
        host, null, "http", List.of("dev"), "dev", PROJECTS, "dev.localhost:8080");
  }
}
