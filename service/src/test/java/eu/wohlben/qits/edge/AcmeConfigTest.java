package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.edge.acme.CertificateNames;
import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The env spellings the bootstrap renders, read back through the mapping.
 *
 * <p>Plain JUnit against an environment source, because the thing worth pinning is the NAME: the
 * bootstrap writes {@code QITS_EDGE_ACME_ADDITIONAL_NAMES} into a compose file and a deploy
 * argument, and a mapping renamed here would leave that key inert with every build green — the
 * certificate would simply come back without the editor hosts on it.
 */
class AcmeConfigTest {

  private static AcmeConfig of(Map<String, String> environment) {
    SmallRyeConfig config =
        new SmallRyeConfigBuilder()
            .withMapping(AcmeConfig.class)
            .withSources(new EnvConfigSource(environment, 300))
            // The "30d" defaults are Quarkus' Duration spelling, not the ISO one a bare SmallRye
            // knows; the runtime converter is what reads them in a deployment too.
            .withConverter(Duration.class, 200, new DurationConverter())
            .build();
    return config.getConfigMapping(AcmeConfig.class);
  }

  @Test
  void theAdditionalNamesArriveUnderTheKeyTheBootstrapRenders() {
    AcmeConfig acme =
        of(Map.of("QITS_EDGE_ACME_ADDITIONAL_NAMES", "editor.acme,editor.gizmo.wohlben.eu"));

    assertEquals(
        Optional.of(List.of("editor.acme", "editor.gizmo.wohlben.eu")), acme.additionalNames());
  }

  @Test
  void anEdgeWithoutTheKeyOrdersOnlyTheDerivedNames() {
    assertTrue(of(Map.of()).additionalNames().isEmpty());
  }

  @Test
  void theCertificatesDomainIsTheStatedOneAndNotAKeyOfItsOwn() throws Exception {
    // QITS_EDGE_ACME_DOMAIN is retired: it was the platform's one stated domain under a second
    // name, and a deployment could set it to something the router disagreed with. A key coming back
    // here would be that second name back.
    assertThrows(
        NoSuchMethodException.class,
        () -> AcmeConfig.class.getMethod("domain"),
        "the acme domain is qits.edge.domain now");

    assertEquals("wohlben.eu", manager("wohlben.eu").domain());
    // Normalised exactly as a served name is, which is the point of there being one value.
    assertEquals("wohlben.eu", manager("  WOHLBEN.eu.  ").domain());
  }

  @Test
  void theSameSansAreOrderedFromTheStatedDomain() {
    // The certificate path end to end, minus the order itself: the domain the manager resolves,
    // fed to the derivation the reconcile feeds it to. The SAN set is character for character what
    // `qits.edge.acme.domain=wohlben.eu` produced before it was retired.
    EdgeCertificateManager manager = manager("wohlben.eu");
    CertificateNames.Names derived =
        CertificateNames.capped(
            manager.domain(),
            List.of("prod", "dev"),
            Map.of("acme", true, "qits", false),
            List.of("editor.gizmo"));

    assertEquals(
        Set.of(
            "wohlben.eu",
            "*.wohlben.eu",
            "*.acme.wohlben.eu",
            "*.prod.acme.wohlben.eu",
            "*.dev.acme.wohlben.eu",
            "*.qits.wohlben.eu",
            "editor.gizmo.wohlben.eu"),
        derived.names());
    assertTrue(derived.droppedProjects().isEmpty());
  }

  /** The manager as the certificate path has it, with everything but the two configs left null. */
  private static EdgeCertificateManager manager(String domain) {
    SmallRyeConfig config =
        new SmallRyeConfigBuilder()
            .withMapping(EdgeConfig.class)
            .withSources(new EnvConfigSource(Map.of("QITS_EDGE_DOMAIN", domain), 300))
            .withConverter(Duration.class, 200, new DurationConverter())
            .build();
    return new EdgeCertificateManager(
        of(Map.of()), config.getConfigMapping(EdgeConfig.class), null, null);
  }
}
