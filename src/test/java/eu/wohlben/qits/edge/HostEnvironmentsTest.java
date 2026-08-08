package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
  void aDomainOfAnyDepthWorksBecauseOnlyTheFirstTwoLabelsAreRead() {
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
