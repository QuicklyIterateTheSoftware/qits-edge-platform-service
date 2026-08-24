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
    // None of them says which environment it is, so the answer is the configured one with the
    // default environment in front — the same origin the login page lives at.
    assertEquals("http://prod.example.com", of("example.com").origin());
    assertEquals("http://prod.example.com", of("staging.example.com").origin());
    assertEquals("http://prod.example.com", of("127.0.0.1").origin());
    assertEquals("http://prod.example.com", of("[::1]:8080").origin());
    assertEquals("http://prod.example.com", of(null).origin());
  }

  @Test
  void theCanonicalOriginKeepsItsOwnPortAndIsNotPrefixedTwice() {
    assertEquals(
        "http://prod.localhost:8080",
        EnvironmentAuthority.of(null, null, "http", ENVIRONMENTS, "prod", "localhost:8080")
            .origin());
    assertEquals(
        "http://prod.example.com",
        EnvironmentAuthority.of(
                "example.com", null, "http", ENVIRONMENTS, "prod", "prod.example.com")
            .origin());
  }

  @Test
  void theSchemeIsTheOutermostHopsWhenThereIsOne() {
    // A TLS terminator in front of the edge is the only hop that knows the answer, and the header
    // is a list with the outermost hop first.
    assertEquals(
        "https://dev.example.com",
        EnvironmentAuthority.of(
                "dev.example.com", "https", "http", ENVIRONMENTS, "prod", "example.com")
            .origin());
    assertEquals(
        "https://dev.example.com",
        EnvironmentAuthority.of(
                "dev.example.com", "https, http", "http", ENVIRONMENTS, "prod", "example.com")
            .origin());
    assertEquals(
        "http://dev.example.com",
        EnvironmentAuthority.of(
                "dev.example.com", "gopher", "http", ENVIRONMENTS, "prod", "example.com")
            .origin(),
        "a value that is not a scheme is not believed");
  }

  private static EnvironmentAuthority of(String host) {
    return EnvironmentAuthority.of(host, null, "http", ENVIRONMENTS, "prod", "example.com");
  }
}
