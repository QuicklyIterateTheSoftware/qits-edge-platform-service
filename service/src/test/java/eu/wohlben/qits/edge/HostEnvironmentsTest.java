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
    // The whole of today's behaviour, unchanged: no app, no rejection.
    assertEquals(new HostEnvironments.Route("dev", null, null), APPS.route("dev.localhost"));
    assertEquals(new HostEnvironments.Route("prod", null, null), APPS.route("example.com"));
    assertEquals(new HostEnvironments.Route("prod", null, null), APPS.route("127.0.0.1:8080"));
    assertEquals(new HostEnvironments.Route("prod", null, null), APPS.route(null));
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
  void anApplicationLabelOnTheApexReachesItInTheDefaultEnvironment() {
    // The environment label is OPTIONAL for the default environment, whose door is the apex: a
    // browser lands on example.com, so its registry is registry.example.com — and the long
    // spelling registry.prod.example.com stays a name for the same place.
    assertEquals(
        new HostEnvironments.Route("prod", "registry", null), APPS.route("registry.example.com"));
    assertEquals(
        new HostEnvironments.Route("prod", "registry", null),
        APPS.route("registry.prod.example.com"));
    assertEquals(
        new HostEnvironments.Route("prod", "githost", null),
        APPS.route("GITHOST.example.co.uk:8080."));
  }

  @Test
  void anEnvironmentNameStillWinsOverAnApplicationOne() {
    // Precedence is unchanged: an environment at either position is read first, so no tier can be
    // hidden by an application whose name looks like one. `staging` is neither here.
    assertEquals(new HostEnvironments.Route("dev", null, null), APPS.route("dev.example.com"));
    assertEquals(new HostEnvironments.Route("prod", null, null), APPS.route("staging.example.com"));
  }

  @Test
  void theShortFormIsOfferedForALabelThisConfigurationDoesNotKnow() {
    // A deployment publishes application names too, and the router joins those on — so an
    // unconfigured first label is OFFERED rather than routed here, and every name that is already
    // routed, is not of that shape, or could not be a label offers nothing.
    assertEquals("ci", APPS.defaultEnvironmentApp("ci.example.com"));
    assertEquals("ci", APPS.defaultEnvironmentApp("CI.example.co.uk:8080."));
    assertNull(APPS.defaultEnvironmentApp("registry.example.com"), "already routed");
    assertNull(APPS.defaultEnvironmentApp("dev.example.com"), "an environment");
    assertNull(APPS.defaultEnvironmentApp("ci.dev.example.com"), "app-shaped already");
    // The apex offers its own first label — `example.com` and `ci.localhost` are the same shape and
    // nothing in a name tells them apart. Nothing is published under it, so the router's lookup is
    // what answers, and it also refuses the apex outright.
    assertEquals("example", APPS.defaultEnvironmentApp("example.com"));
    assertNull(APPS.defaultEnvironmentApp("127.0.0.1"), "an address carries no name");
    assertNull(APPS.defaultEnvironmentApp("localhost"), "one label is not $app.$domain");
    assertNull(APPS.defaultEnvironmentApp(null));
  }

  @Test
  void anUnknownLabelOnTheApexIsStillTheDefaultEnvironment() {
    // NOT a 404: the app rule's refusal is for a name in front of a KNOWN environment. A mistyped
    // or decommissioned name on the apex reaches the platform's own page, as it always has.
    assertEquals(new HostEnvironments.Route("prod", null, null), APPS.route("ci.example.com"));
    assertNull(APPS.route("ci.example.com").unknownApp());
  }

  @Test
  void aNameThatIsNotAppShapedIsUntouchedByTheAppRule() {
    // `example` is not an environment, so `staging.example.com` names no app position at all and
    // stays what it has always been: the default gateway's.
    assertNull(APPS.route("staging.example.com").unknownApp());
    assertNull(APPS.route("anything.at.all.example.com").unknownApp());
    assertEquals("prod", APPS.route("staging.example.com").environment());
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
