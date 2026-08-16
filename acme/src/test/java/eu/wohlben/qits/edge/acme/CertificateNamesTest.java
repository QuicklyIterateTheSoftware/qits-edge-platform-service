package eu.wohlben.qits.edge.acme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class CertificateNamesTest {

  @Test
  void coversTheApexPlatformServicesAndOneProjectLabelPerEnvironment() {
    assertThat(CertificateNames.of("wohlben.eu", List.of("prod", "dev")))
        .containsExactlyInAnyOrder(
            "wohlben.eu", "*.wohlben.eu", "*.prod.wohlben.eu", "*.dev.wohlben.eu");
  }

  @Test
  void rejectsNamesThatWouldEscapeTheCertificateShape() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CertificateNames.of("wohlben.eu", List.of("project.dev")));
  }
}
