package eu.wohlben.qits.edge.acme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.Collections;
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
    // What retires the extra-SAN-per-project debt: editor.acme.dev.wohlben.eu is covered because
    // the project is known, not because somebody remembered to write it into a bootstrap key.
    assertThat(
            CertificateNames.capped("wohlben.eu", List.of(), List.of("acme"), List.of())
                .names()
                .contains("*.acme.wohlben.eu"))
        .isTrue();
  }

  @Test
  void theProjectTiersAreTheCrossProductWithTheEnvironments() {
    assertThat(
            CertificateNames.capped(
                    "wohlben.eu", List.of("prod", "dev"), List.of("acme", "gizmo"), List.of())
                .names())
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
            CertificateNames.capped(
                    "wohlben.eu", List.of("prod", "dev"), List.of("acme"), List.of("editor.legacy"))
                .names())
        .containsExactlyElementsOf(
            CertificateNames.capped(
                    "wohlben.eu", List.of("prod", "dev"), List.of("acme"), List.of("editor.legacy"))
                .names());
  }

  @Test
  void aProjectSlugThatIsNotALabelIsRefusedByName() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                CertificateNames.capped(
                    "wohlben.eu", List.of("prod"), List.of("acme.evil"), List.of()))
        .withMessageContaining("acme.evil");
  }

  @Test
  void anEstateTooLargeForOneCertificateDropsProjectsRatherThanTheOrder() {
    // 40 projects across 2 environments would be 2 + 2 + 40 + 80 = 124 names. This used to throw,
    // and the throw happened before the manager's due-check — so one project past the ceiling
    // stopped EXPIRY renewals too and the platform's only TLS terminator went dark ninety days
    // later. The set that comes back now is short of some projects and is still orderable.
    CertificateNames.Names derived =
        CertificateNames.capped("wohlben.eu", List.of("prod", "dev"), projects(40), List.of());

    assertThat(derived.names()).hasSize(CertificateNames.MAX_SANS);
    assertThat(derived.names()).contains("wohlben.eu", "*.wohlben.eu", "*.prod.wohlben.eu");
    // 2 fixed + 2 environments leaves 96 for the tiers, and a project costs THREE — its own
    // wildcard and one per environment — so 32 fit and the last 8 do not.
    assertThat(derived.droppedProjects()).hasSize(8);
  }

  @Test
  void theDroppedProjectsAreTheEndOfTheSortedOrderAndTheSameOnesEveryTime() {
    // Deterministic, and deterministic in the SLUGS rather than in the arrival order: an estate
    // that has not changed must not reshuffle which projects are covered from one reconcile to the
    // next, or a name that answered this morning fails its handshake this afternoon.
    List<String> shuffled = new ArrayList<>(projects(40));
    Collections.reverse(shuffled);

    CertificateNames.Names sorted =
        CertificateNames.capped("wohlben.eu", List.of("prod", "dev"), projects(40), List.of());
    CertificateNames.Names reversed =
        CertificateNames.capped("wohlben.eu", List.of("prod", "dev"), shuffled, List.of());

    assertThat(reversed.droppedProjects()).isEqualTo(sorted.droppedProjects());
    // `project-9` sorts last among these forty spellings, so it is the far end of the tail that
    // was dropped — the drop runs from the END of the sorted order, whatever order they arrived in.
    assertThat(sorted.droppedProjects().getLast()).isEqualTo("project-9");
    assertThat(sorted.names()).doesNotContain("*.project-9.wohlben.eu");
    assertThat(reversed.names()).containsExactlyInAnyOrderElementsOf(sorted.names());
  }

  @Test
  void aProjectIsWhollyOnTheCertificateOrWhollyOffIt() {
    // Half a project is an origin that answers in one environment and fails the handshake in the
    // next, which is worse than the project being absent: nothing about it is visible until the
    // second environment is dialled.
    CertificateNames.Names derived =
        CertificateNames.capped(
            "wohlben.eu", List.of("prod", "dev", "ci"), projects(40), List.of());

    for (String project : projects(40)) {
      boolean dropped = derived.droppedProjects().contains(project);
      for (String tier :
          List.of(
              "*." + project + ".wohlben.eu",
              "*." + project + ".prod.wohlben.eu",
              "*." + project + ".dev.wohlben.eu",
              "*." + project + ".ci.wohlben.eu")) {
        assertThat(derived.names().contains(tier)).describedAs(tier).isEqualTo(!dropped);
      }
    }
    assertThat(derived.names()).hasSizeLessThanOrEqualTo(CertificateNames.MAX_SANS);
  }

  @Test
  void theEnvironmentTiersAndTheAdditionalNamesAreNeverDropped() {
    // They are what the platform itself answers on, and they are configuration rather than a
    // projection that grew — so the cap is spent on them first and the projects take what is left.
    CertificateNames.Names derived =
        CertificateNames.capped(
            "wohlben.eu",
            List.of("prod", "dev", "ci"),
            projects(40),
            List.of("editor.legacy", "status.wohlben.eu"));

    assertThat(derived.names())
        .contains(
            "wohlben.eu",
            "*.wohlben.eu",
            "*.prod.wohlben.eu",
            "*.dev.wohlben.eu",
            "*.ci.wohlben.eu",
            "editor.legacy.wohlben.eu",
            "status.wohlben.eu");
    assertThat(derived.droppedProjects()).isNotEmpty();
  }

  @Test
  void anEdgeThatFillsTheCertificateBeforeAnyProjectIsStillARefusal() {
    // The one arm that still throws: no project tier is even attempted, so there is nothing to drop
    // and the numbers came from a deployment rather than from a project somebody created. It is a
    // configuration to correct, and the message says which two terms produced it.
    List<String> environments = new ArrayList<>();
    for (int index = 0; index < 99; index++) {
      environments.add("env-" + index);
    }

    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> CertificateNames.capped("wohlben.eu", environments, List.of("acme"), List.of()))
        .withMessageContaining("101")
        .withMessageContaining(String.valueOf(CertificateNames.MAX_SANS));
  }

  /** Forty slugs, in the arrival order a sorted projection hands them over in. */
  private static List<String> projects(int count) {
    List<String> projects = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      projects.add("project-" + index);
    }
    Collections.sort(projects);
    return projects;
  }
}
