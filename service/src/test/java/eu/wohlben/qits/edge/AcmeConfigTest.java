package eu.wohlben.qits.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.runtime.configuration.DurationConverter;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        of(
            Map.of(
                "QITS_EDGE_ACME_DOMAIN", "wohlben.eu",
                "QITS_EDGE_ACME_ADDITIONAL_NAMES", "editor.acme,editor.gizmo.wohlben.eu"));

    assertEquals(Optional.of("wohlben.eu"), acme.domain());
    assertEquals(
        Optional.of(List.of("editor.acme", "editor.gizmo.wohlben.eu")), acme.additionalNames());
  }

  @Test
  void anEdgeWithoutTheKeyOrdersOnlyTheDerivedNames() {
    assertTrue(of(Map.of("QITS_EDGE_ACME_DOMAIN", "wohlben.eu")).additionalNames().isEmpty());
  }
}
