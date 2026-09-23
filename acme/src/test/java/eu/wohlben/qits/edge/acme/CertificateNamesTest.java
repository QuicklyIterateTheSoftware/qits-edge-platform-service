package eu.wohlben.qits.edge.acme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CertificateNamesTest {

  @Test
  void withoutAnyProjectTheSetIsTheApexAndItsWildcard() {
    // An environment is a tier INSIDE a project now, so a platform that knows no projects has no
    // environment names to order at all: `dev.wohlben.eu` is not a name.
    assertThat(CertificateNames.of("wohlben.eu", List.of("prod", "dev")))
        .containsExactly("wohlben.eu", "*.wohlben.eu");
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
            "wohlben.eu", "*.wohlben.eu", "editor.qits-qits.wohlben.eu", "editor.gizmo.wohlben.eu");
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
        .containsExactly("wohlben.eu", "*.wohlben.eu", "editor.acme.wohlben.eu");
  }

  @Test
  void readsOneValueHoldingSeveralNames() {
    assertThat(
            CertificateNames.of(
                "wohlben.eu", List.of("prod"), List.of("editor.acme, editor.gizmo")))
        .containsExactly(
            "wohlben.eu", "*.wohlben.eu", "editor.acme.wohlben.eu", "editor.gizmo.wohlben.eu");
  }

  @Test
  void refusesAnAdditionalNameThatIsNotAName() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> CertificateNames.of("wohlben.eu", List.of("prod"), List.of("*.editor.acme")));
  }

  @Test
  void aKnownProjectIsCoveredAtItsOwnDepthWithoutAnyAdditionalName() {
    // What retires the extra-SAN-per-project debt: the project's own door and everything served
    // under it is covered because the project is known, not because somebody remembered to write
    // it into a bootstrap key.
    assertThat(
            CertificateNames.capped("wohlben.eu", List.of(), envless("acme"), List.of())
                .names()
                .contains("*.acme.wohlben.eu"))
        .isTrue();
  }

  @Test
  void anEnvLessEstateCostsTwoPlusOneNamePerProject() {
    // The whole point of the re-tiering: 2 + P. The environment list is configured and is simply
    // not a tier for these projects, so it contributes nothing whatever its length.
    CertificateNames.Names derived =
        CertificateNames.capped(
            "wohlben.eu", List.of("prod", "dev", "ci"), envless("acme", "gizmo"), List.of());

    assertThat(derived.names())
        .containsExactly("wohlben.eu", "*.wohlben.eu", "*.acme.wohlben.eu", "*.gizmo.wohlben.eu");
    assertThat(derived.names()).hasSize(2 + 2);
    assertThat(derived.droppedProjects()).isEmpty();
  }

  @Test
  void anEnvSupportingProjectAlsoCostsOneNamePerEnvironment() {
    // 2 + P + P·E, with the environment label INSIDE the project label: ci.dev.acme.wohlben.eu is
    // an app of acme in dev, and *.acme.wohlben.eu is what acme's environment doors are served on.
    CertificateNames.Names derived =
        CertificateNames.capped(
            "wohlben.eu", List.of("prod", "dev"), withEnvironments("acme", "gizmo"), List.of());

    assertThat(derived.names())
        .containsExactly(
            "wohlben.eu",
            "*.wohlben.eu",
            "*.acme.wohlben.eu",
            "*.gizmo.wohlben.eu",
            "*.prod.acme.wohlben.eu",
            "*.dev.acme.wohlben.eu",
            "*.prod.gizmo.wohlben.eu",
            "*.dev.gizmo.wohlben.eu");
    assertThat(derived.names()).hasSize(2 + 2 + 2 * 2);
  }

  @Test
  void theTopLevelEnvironmentTierIsGone() {
    // An environment is inside a project, so there is no `dev.wohlben.eu` and nothing under it.
    // Asserted against an estate that has environments in play, so this is the tier's absence and
    // not merely an estate with no environments configured.
    CertificateNames.Names derived =
        CertificateNames.capped(
            "wohlben.eu", List.of("prod", "dev"), withEnvironments("acme"), List.of());

    assertThat(derived.names()).doesNotContain("*.prod.wohlben.eu", "*.dev.wohlben.eu");
  }

  @Test
  void theTwoKindsOfProjectSitOnOneCertificateTogether() {
    // A mixed estate is the ordinary one during the migration, and the flag is per project rather
    // than per platform: `qits` is env-less and costs one name, `acme` has environments.
    LinkedHashMap<String, Boolean> projects = new LinkedHashMap<>();
    projects.put("qits", false);
    projects.put("acme", true);

    assertThat(CertificateNames.capped("wohlben.eu", List.of("dev"), projects, List.of()).names())
        .containsExactly(
            "wohlben.eu",
            "*.wohlben.eu",
            "*.acme.wohlben.eu",
            "*.qits.wohlben.eu",
            "*.dev.acme.wohlben.eu");
  }

  @Test
  void aProjectWithNoRecordedFlagIsReadAsSupportingEnvironments() {
    // The same compatibility rule the projection's column default carries: absence means "has
    // environments", which is what every project had before the flag existed. Under-ordering is
    // the failure that shows as a handshake error.
    LinkedHashMap<String, Boolean> projects = new LinkedHashMap<>();
    projects.put("acme", null);

    assertThat(CertificateNames.capped("wohlben.eu", List.of("dev"), projects, List.of()).names())
        .contains("*.dev.acme.wohlben.eu");
  }

  @Test
  void theOrderIsTheOneItWasBuiltIn() {
    // Twice from the same input: the SAN order is a property of the projection, not of a hash seed
    // that moves with the restart — an order whose names merely permuted is a needless renewal.
    assertThat(
            CertificateNames.capped(
                    "wohlben.eu",
                    List.of("prod", "dev"),
                    withEnvironments("acme"),
                    List.of("editor.legacy"))
                .names())
        .containsExactlyElementsOf(
            CertificateNames.capped(
                    "wohlben.eu",
                    List.of("prod", "dev"),
                    withEnvironments("acme"),
                    List.of("editor.legacy"))
                .names());
  }

  @Test
  void aProjectSlugThatIsNotALabelIsRefusedByName() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                CertificateNames.capped(
                    "wohlben.eu", List.of("prod"), envless("acme.evil"), List.of()))
        .withMessageContaining("acme.evil");
  }

  @Test
  void anEnvLessEstateReachesTheCeilingAtNinetyEightProjects() {
    // 2 + P, so 98 fit exactly and the 99th does not. Far further off than the twenty-four the old
    // cross product allowed, and still reachable — which is why the drop policy stays.
    CertificateNames.Names derived =
        CertificateNames.capped(
            "wohlben.eu", List.of("prod", "dev"), envless(projects(120)), List.of());

    assertThat(derived.names()).hasSize(CertificateNames.MAX_SANS);
    assertThat(derived.names()).contains("wohlben.eu", "*.wohlben.eu");
    assertThat(derived.droppedProjects()).hasSize(120 - 98);
  }

  @Test
  void anEstateTooLargeForOneCertificateDropsProjectsRatherThanTheOrder() {
    // 40 env-supporting projects across 2 environments would be 2 + 40 + 80 = 122 names. This used
    // to throw, and the throw happened before the manager's due-check — so one project past the
    // ceiling stopped EXPIRY renewals too and the platform's only TLS terminator went dark ninety
    // days later. The set that comes back now is short of some projects and is still orderable.
    CertificateNames.Names derived =
        CertificateNames.capped(
            "wohlben.eu", List.of("prod", "dev"), withEnvironments(projects(40)), List.of());

    assertThat(derived.names()).hasSize(98);
    assertThat(derived.names()).contains("wohlben.eu", "*.wohlben.eu");
    // 2 fixed leaves 98 for the tiers, and an env-supporting project costs THREE here — its own
    // wildcard and one per environment — so 32 fit (2 + 96 = 98, and a 33rd would be 101) and the
    // last 8 do not.
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
        CertificateNames.capped(
            "wohlben.eu", List.of("prod", "dev"), withEnvironments(projects(40)), List.of());
    CertificateNames.Names reversed =
        CertificateNames.capped(
            "wohlben.eu", List.of("prod", "dev"), withEnvironments(shuffled), List.of());

    assertThat(reversed.droppedProjects()).isEqualTo(sorted.droppedProjects());
    // `project-9` sorts last among these forty spellings, so it is the far end of the tail that
    // was dropped — the drop runs from the END of the sorted order, whatever order they arrived in.
    assertThat(sorted.droppedProjects().getLast()).isEqualTo("project-9");
    assertThat(sorted.names()).doesNotContain("*.project-9.wohlben.eu");
    // Exact ORDER, not merely the same set: the SAN list a certificate carries is written in this
    // order, so an arrival order that permuted it would reorder the names on every restart.
    assertThat(reversed.names()).containsExactlyElementsOf(sorted.names());
  }

  @Test
  void aCheapProjectBehindADroppedOneIsDroppedToo() {
    // The kept set is a PREFIX of the sorted order. It matters more now that projects cost
    // different amounts: without it an env-less slug would slip onto the certificate past an
    // env-supporting one that did not fit, and which projects are covered would depend on the
    // shape of the estate rather than on the slugs.
    LinkedHashMap<String, Boolean> projects = new LinkedHashMap<>();
    for (String project : projects(40)) {
      projects.put(project, true);
    }
    projects.put("zzz-tiny", false);

    CertificateNames.Names derived =
        CertificateNames.capped("wohlben.eu", List.of("prod", "dev"), projects, List.of());

    assertThat(derived.droppedProjects()).contains("zzz-tiny");
    assertThat(derived.names()).doesNotContain("*.zzz-tiny.wohlben.eu");
  }

  @Test
  void theProjectTiersAreEmittedSortedWhateverOrderTheyArriveIn() {
    // Nothing was dropped here — this is the ORDER of a set that fits, which is a separate promise
    // from which projects fit. It used to come out in the caller's order, and read as deterministic
    // only because EdgeProjects happens to `order by slug`.
    assertThat(
            CertificateNames.capped(
                    "wohlben.eu", List.of("dev"), withEnvironments("gizmo", "acme"), List.of())
                .names())
        .containsExactly(
            "wohlben.eu",
            "*.wohlben.eu",
            "*.acme.wohlben.eu",
            "*.gizmo.wohlben.eu",
            "*.dev.acme.wohlben.eu",
            "*.dev.gizmo.wohlben.eu");
  }

  @Test
  void aProjectIsWhollyOnTheCertificateOrWhollyOffIt() {
    // Half a project is an origin that answers in one environment and fails the handshake in the
    // next, which is worse than the project being absent: nothing about it is visible until the
    // second environment is dialled.
    CertificateNames.Names derived =
        CertificateNames.capped(
            "wohlben.eu", List.of("prod", "dev", "ci"), withEnvironments(projects(40)), List.of());

    for (String project : projects(40)) {
      boolean dropped = derived.droppedProjects().contains(project);
      for (String tier :
          List.of(
              "*." + project + ".wohlben.eu",
              "*.prod." + project + ".wohlben.eu",
              "*.dev." + project + ".wohlben.eu",
              "*.ci." + project + ".wohlben.eu")) {
        assertThat(derived.names().contains(tier)).describedAs(tier).isEqualTo(!dropped);
      }
    }
    assertThat(derived.names()).hasSizeLessThanOrEqualTo(CertificateNames.MAX_SANS);
  }

  @Test
  void theAdditionalNamesAreNeverDropped() {
    // They are what the platform itself answers on, and they are configuration rather than a
    // projection that grew — so the cap is spent on them first and the projects take what is left.
    CertificateNames.Names derived =
        CertificateNames.capped(
            "wohlben.eu",
            List.of("prod", "dev", "ci"),
            withEnvironments(projects(40)),
            List.of("editor.legacy", "status.wohlben.eu"));

    assertThat(derived.names())
        .contains("wohlben.eu", "*.wohlben.eu", "editor.legacy.wohlben.eu", "status.wohlben.eu");
    assertThat(derived.droppedProjects()).isNotEmpty();
  }

  @Test
  void anEdgeThatFillsTheCertificateBeforeAnyProjectIsStillARefusal() {
    // The one arm that still throws: no project tier is even attempted, so there is nothing to drop
    // and the number came from a deployment rather than from a project somebody created. Only the
    // additional names can produce it now — the environments are no longer a top-level tier, so a
    // long environment list costs the fixed set nothing.
    List<String> additional = new ArrayList<>();
    for (int index = 0; index < 99; index++) {
      additional.add("name-" + index);
    }

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                CertificateNames.capped("wohlben.eu", List.of("prod"), envless("acme"), additional))
        .withMessageContaining("101")
        .withMessageContaining(String.valueOf(CertificateNames.MAX_SANS));
  }

  @Test
  void aLongEnvironmentListNoLongerFillsTheCertificateOnItsOwn() {
    // What the refusal above used to be triggered by. Ninety-nine environments and one env-less
    // project is three names, because an environment is only ever a label inside a project.
    List<String> environments = new ArrayList<>();
    for (int index = 0; index < 99; index++) {
      environments.add("env-" + index);
    }

    assertThat(
            CertificateNames.capped("wohlben.eu", environments, envless("acme"), List.of()).names())
        .containsExactly("wohlben.eu", "*.wohlben.eu", "*.acme.wohlben.eu");
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

  private static Map<String, Boolean> envless(String... slugs) {
    return envless(List.of(slugs));
  }

  private static Map<String, Boolean> envless(List<String> slugs) {
    return flagged(slugs, false);
  }

  private static Map<String, Boolean> withEnvironments(String... slugs) {
    return withEnvironments(List.of(slugs));
  }

  private static Map<String, Boolean> withEnvironments(List<String> slugs) {
    return flagged(slugs, true);
  }

  /**
   * A LinkedHashMap, never {@code Map.of}: the copy factories salt their iteration order per JVM,
   * and the arrival-order tests here are about an order that is stable enough to compare.
   */
  private static Map<String, Boolean> flagged(List<String> slugs, boolean supportsEnvironments) {
    LinkedHashMap<String, Boolean> projects = new LinkedHashMap<>();
    for (String slug : slugs) {
      projects.put(slug, supportsEnvironments);
    }
    return projects;
  }
}
