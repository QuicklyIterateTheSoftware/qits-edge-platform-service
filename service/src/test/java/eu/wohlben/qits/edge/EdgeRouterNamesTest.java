package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The two decisions {@code EdgeRouter} makes out of a name alone, asserted without a boot.
 *
 * <p>Both are about caller input reaching an answer. Recognising the apex decides whether a request
 * is served at all, and it is a comparison against a header a client writes; the unknown-app 404
 * writes part of that header back out. Neither needs a Vert.x server to be wrong, and a
 * {@code @QuarkusTest} for either would be a restart the socket tests cannot afford — see {@code
 * EdgeRoutingTest}'s javadoc.
 */
class EdgeRouterNamesTest {

  @Test
  void theApexIsRecognisedThroughEveryOrdinarySpellingOfIt() {
    assertTrue(EdgeRouter.isApex("example.com", "example.com", "prod"));
    // FIXED. A resolver writes the root dot and a client may send it; this compared a bare strip(),
    // so `example.com.` missed the apex and was answered a 404 offering the name it was already on.
    assertTrue(EdgeRouter.isApex("example.com.", "example.com", "prod"), "the root dot");
    assertTrue(EdgeRouter.isApex("EXAMPLE.com", "example.com", "prod"), "letter case");
    assertTrue(EdgeRouter.isApex("  example.com  ", "example.com", "prod"), "surrounding space");
    // Either spelling of the canonical origin names the same apex, as EnvironmentAuthority reads
    // it.
    assertTrue(EdgeRouter.isApex("example.com.", "prod.example.com", "prod"));
    // The local apex is one label and the same rule.
    assertTrue(EdgeRouter.isApex("localhost.", "dev.localhost:8080", "dev"));
  }

  @Test
  void aNameThatIsNotTheApexIsStillNotTheApex() {
    assertFalse(EdgeRouter.isApex("ci.example.com", "example.com", "prod"));
    assertFalse(EdgeRouter.isApex("prod.example.com", "example.com", "prod"));
    assertFalse(EdgeRouter.isApex("evil.com.", "example.com", "prod"));
    assertFalse(EdgeRouter.isApex(null, "example.com", "prod"), "a request with no Host");
    assertFalse(EdgeRouter.isApex("example.com", null, "prod"), "no canonical origin configured");
  }

  @Test
  void theUnknownAppAnswerEchoesTheLabelOnlyWhenItIsOne() {
    Set<String> apps = Set.of("registry");

    assertTrue(
        EdgeRouter.unknownAppBody(new HostEnvironments.Route("dev", null, "nosuchapp"), apps)
            .contains("`nosuchapp` is not an application"));
    assertTrue(
        EdgeRouter.unknownAppBody(
                new HostEnvironments.Route("dev", null, "editor", "acme", true), apps)
            .contains("the project `acme`"));
  }

  @Test
  void aLabelThatIsNotOneIsDescribedRatherThanQuotedBack() {
    // The label is the first one of a Host header, so it is attacker input wherever it is written.
    // `Host: .prod.example.com` produced a sentence about an empty name, and anything a header
    // parser let through came back verbatim into a body a browser renders.
    Set<String> apps = Set.of("registry");

    for (String label : new String[] {"", "<script>alert(1)</script>", "a b", "-nope-", null}) {
      String body = EdgeRouter.unknownAppBody(new HostEnvironments.Route("dev", null, label), apps);
      assertTrue(body.startsWith("That first label is not an application"), body);
      if (label != null && !label.isEmpty()) {
        assertFalse(body.contains(label), body);
      }
    }
  }
}
