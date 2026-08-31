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

  @Test
  void withoutAdditionalNamesTheDerivedSetIsUntouched() {
    assertThat(CertificateNames.of("wohlben.eu", List.of("prod", "dev"), List.of()))
        .containsExactlyElementsOf(CertificateNames.of("wohlben.eu", List.of("prod", "dev")));
  }

  @Test
  void carriesTheNamesNoWildcardCanReach() {
    assertThat(
            CertificateNames.of(
                "wohlben.eu",
                List.of("prod"),
                List.of("editor.qits-qits", "editor.gizmo.wohlben.eu")))
        .containsExactly(
            "wohlben.eu",
            "*.wohlben.eu",
            "*.prod.wohlben.eu",
            "editor.qits-qits.wohlben.eu",
            "editor.gizmo.wohlben.eu");
  }

  @Test
  void aRelativeNameAndItsWholeSpellingAreTheSameName() {
    assertThat(CertificateNames.of("wohlben.eu", List.of("prod"), List.of("editor.acme")))
        .containsExactlyElementsOf(
            CertificateNames.of("wohlben.eu", List.of("prod"), List.of("editor.acme.wohlben.eu.")));
  }

  @Test
  void normalizesCaseWhitespaceAndBlanksAndDedupesAgainstTheDerivedNames() {
    assertThat(
            CertificateNames.of(
                "wohlben.eu",
                List.of("prod"),
                List.of("  Editor.ACME  ", "", "editor.acme.wohlben.eu", "wohlben.eu")))
        .containsExactly(
            "wohlben.eu", "*.wohlben.eu", "*.prod.wohlben.eu", "editor.acme.wohlben.eu");
  }

  @Test
  void readsOneValueHoldingSeveralNames() {
    assertThat(
            CertificateNames.of(
                "wohlben.eu", List.of("prod"), List.of("editor.acme, editor.gizmo")))
        .containsExactly(
            "wohlben.eu",
            "*.wohlben.eu",
            "*.prod.wohlben.eu",
            "editor.acme.wohlben.eu",
            "editor.gizmo.wohlben.eu");
  }

  @Test
  void refusesAnAdditionalNameThatIsNotAName() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> CertificateNames.of("wohlben.eu", List.of("prod"), List.of("*.editor.acme")));
  }
}
