package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.edge.HostEnvironments.Reading;
import eu.wohlben.qits.edge.HostEnvironments.Route;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The decisions {@code EdgeRouter} makes out of names alone, asserted without a boot.
 *
 * <p>Where the stated domain comes from is the first of them: every reading hangs off it, and it is
 * a value rather than something derived from a request. The rest are about caller input reaching an
 * answer — the 404 bodies write a label a client chose back out. None of them needs a Vert.x server
 * to be wrong, and a {@code @QuarkusTest} for any of them would be a restart the socket tests
 * cannot afford — see {@code EdgeRoutingTest}'s javadoc.
 */
class EdgeRouterNamesTest {

  @Test
  void theStatedDomainIsTheCertificatesWhenThereIsOne() {
    // The edge orders the names inside qits.edge.acme.domain, so that value IS the domain the
    // estate lives in — and it wins over a canonical origin that carries a label in front of it.
    assertEquals(
        "wohlben.eu", EdgeRouter.domain(Optional.of("wohlben.eu"), "https://prod.wohlben.eu"));
    assertEquals("wohlben.eu", EdgeRouter.domain(Optional.of("  wohlben.eu  "), "wohlben.eu"));
  }

  @Test
  void aCloneWithAcmeOffFallsBackToTheCanonicalAuthority() {
    // The local case, which is where the domain is `localhost`: no certificate, so no acme domain,
    // and the canonical origin's authority is the same value with a port on it.
    assertEquals("localhost", EdgeRouter.domain(Optional.empty(), "localhost:8080"));
    assertEquals("example.com", EdgeRouter.domain(Optional.of("  "), "example.com"));
    assertEquals(
        "", EdgeRouter.domain(Optional.empty(), null), "and nothing configured is nothing");
  }

  @Test
  void theUnknownAppAnswerEchoesTheLabelOnlyWhenItIsOne() {
    Set<String> apps = Set.of("registry");

    assertTrue(
        EdgeRouter.unknownAppBody(new HostEnvironments.Route("dev", null, "nosuchapp"), apps)
            .contains("`nosuchapp` is not an application"));
    assertTrue(
        EdgeRouter.unknownAppBody(
                new HostEnvironments.Route("dev", null, "editor", "acme", Reading.UNKNOWN_APP),
                apps)
            .contains("the project `acme`"));
  }

  @Test
  void aLabelThatIsNotOneIsDescribedRatherThanQuotedBack() {
    // The label is the leftmost one of a Host header, so it is attacker input wherever it is
    // written. `Host: .dev.acme.example.com` produced a sentence about an empty name, and anything
    // a
    // header parser let through came back verbatim into a body a browser renders.
    Set<String> apps = Set.of("registry");

    for (String label : new String[] {"", "<script>alert(1)</script>", "a b", "-nope-", null}) {
      String body = EdgeRouter.unknownAppBody(new HostEnvironments.Route("dev", null, label), apps);
      assertTrue(body.startsWith("That first label is not an application"), body);
      if (label != null && !label.isEmpty()) {
        assertFalse(body.contains(label), body);
      }
    }
  }

  @Test
  void anUnknownProjectLabelIsDescribedRatherThanQuotedBackToo() {
    // The same rule one tier to the right: the project label is caller input as well, and this
    // sentence is the one a mistyped project name gets.
    assertTrue(
        EdgeRouter.unknownProjectBody(Route.unknownProject("prod", "nosuch"), "wohlben.eu")
            .startsWith("`nosuch` is not a project"));
    String laundered =
        EdgeRouter.unknownProjectBody(Route.unknownProject("prod", null), "wohlben.eu");
    assertTrue(laundered.startsWith("That label is not a project"), laundered);
    assertTrue(laundered.contains("wohlben.eu"), "and it names the domain it was read from");
  }

  @Test
  void eachDoorSaysWhichDoorItIs() {
    assertTrue(
        EdgeRouter.doorBody(Route.apex("prod"), "wohlben.eu")
            .contains("<app>.<project>.wohlben.eu"),
        "the apex offers the grammar and names no project at all");
    assertTrue(
        EdgeRouter.doorBody(Route.projectDoor("prod", "acme"), "wohlben.eu")
            .contains("<app>.acme.wohlben.eu"));
    assertTrue(
        EdgeRouter.doorBody(Route.environmentDoor("dev", "acme"), "wohlben.eu")
            .contains("<app>.dev.acme.wohlben.eu"));
  }

  @Test
  void aNameOutsideTheGrammarIsToldWhichWayItIsWrong() {
    assertTrue(
        EdgeRouter.unreadableBody(Route.unreadable("prod", null), "wohlben.eu")
            .contains("more labels than the grammar has"));
    assertTrue(
        EdgeRouter.unreadableBody(Route.unreadable("prod", "acme"), "wohlben.eu")
            .contains("does not have an environment by that name"));
  }
}
