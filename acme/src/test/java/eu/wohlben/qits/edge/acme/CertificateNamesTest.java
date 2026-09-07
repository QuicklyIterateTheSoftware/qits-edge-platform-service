package eu.wohlben.qits.edge.acme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
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

  @Test
  void aKnownProjectIsCoveredAtItsOwnDepthWithoutAnyAdditionalName() {
    // What retires the extra-SAN-per-project debt: editor.acme.wohlben.eu is covered because the
    // project is known, not because somebody remembered to write it into a bootstrap key.
    assertThat(
            CertificateNames.of("wohlben.eu", List.of(), List.of("acme"), List.of())
                .contains("*.acme.wohlben.eu"))
        .isTrue();
  }

  @Test
  void theProjectTiersAreTheCrossProductWithTheEnvironments() {
    assertThat(
            CertificateNames.of(
                "wohlben.eu", List.of("prod", "dev"), List.of("acme", "gizmo"), List.of()))
        .containsExactly(
            "wohlben.eu",
            "*.wohlben.eu",
            "*.prod.wohlben.eu",
            "*.dev.wohlben.eu",
            "*.acme.wohlben.eu",
            "*.gizmo.wohlben.eu",
            "*.acme.prod.wohlben.eu",
            "*.acme.dev.wohlben.eu",
            "*.gizmo.prod.wohlben.eu",
            "*.gizmo.dev.wohlben.eu");
  }

  @Test
  void theOrderIsTheOneItWasBuiltIn() {
    // Twice from the same input: the SAN order is a property of the projection, not of a hash seed
    // that moves with the restart — an order whose names merely permuted is a needless renewal.
    assertThat(
            CertificateNames.of(
                "wohlben.eu", List.of("prod", "dev"), List.of("acme"), List.of("editor.legacy")))
        .containsExactlyElementsOf(
            CertificateNames.of(
                "wohlben.eu", List.of("prod", "dev"), List.of("acme"), List.of("editor.legacy")));
  }

  @Test
  void aProjectSlugThatIsNotALabelIsRefusedByName() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                CertificateNames.of("wohlben.eu", List.of("prod"), List.of("acme.evil"), List.of()))
        .withMessageContaining("acme.evil");
  }

  @Test
  void anEstateTooLargeForOneCertificateIsRefusedHereRatherThanByLetsEncrypt() {
    // 40 projects across 2 environments is 2 + 2 + 40 + 80 = 124 names. The refusal is local so the
    // installed certificate stays active; an order sent anyway would spend a rate-limited request
    // to be told the same thing.
    List<String> projects = new ArrayList<>();
    for (int index = 0; index < 40; index++) {
      projects.add("project-" + index);
    }

    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> CertificateNames.of("wohlben.eu", List.of("prod", "dev"), projects, List.of()))
        .withMessageContaining("124")
        .withMessageContaining(String.valueOf(CertificateNames.MAX_SANS));
  }
}
