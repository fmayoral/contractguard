package com.contractguard.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.contractguard.domain.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Construction-time invariants of the domain value types. */
class DomainInvariantsTest {

    @Test
    void evidenceRejectsInvalidLineRanges() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ImpactEvidence(
                "ev-1", "ch-1", "a.java", 0, 5, "s", "t", "USES", "h"));
        assertThatIllegalArgumentException().isThrownBy(() -> new ImpactEvidence(
                "ev-1", "ch-1", "a.java", 10, 5, "s", "t", "USES", "h"));
    }

    @Test
    void assessmentWithoutEvidenceIsUnrepresentable() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> Fixtures.assessment("as-1", "ch-1", List.of()));
    }

    @Test
    void emptyMigrationPlanIsUnrepresentable() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new MigrationPlan("p", 1, "h", List.of(), T0));
    }

    @Test
    void planVersionStartsAtOne() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new MigrationPlan("p", 0, "h", List.of(Fixtures.planItem("i")), T0));
    }

    @Test
    void planItemWithoutFilesIsUnrepresentable() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PlanItem(
                "it", "obj", List.of(), "action", List.of(), "maven-verify", "low", "revert", List.of()));
    }

    @Test
    void approvedFilesUnionsSourceAndTestFiles() {
        MigrationPlan plan = Fixtures.plan(List.of(Fixtures.planItem("it-1")));
        assertThat(plan.approvedFiles())
                .containsExactly("src/main/java/App.java", "src/test/java/AppTest.java");
    }

    @Test
    void successfulValidationRequiresExitCodeZero() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ValidationResult(
                1, "maven-verify", 1, T0, Duration.ofSeconds(1), "s", null, true));
    }

    @Test
    void validationAttemptIsBoundedToTwo() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ValidationResult(
                3, "maven-verify", 0, T0, Duration.ofSeconds(1), "s", null, true));
    }

    @Test
    void patchAttemptIsBoundedToTwo() {
        assertThatIllegalArgumentException().isThrownBy(() -> Fixtures.patch("p", 3));
    }

    @Test
    void appliedPatchRequiresTimestamp() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PatchArtifact(
                "p", "r", 1, "diff", List.of(), PatchArtifact.CheckStatus.APPLIED, null));
        PatchArtifact applied = Fixtures.patch("p", 1).asApplied(T0);
        assertThat(applied.appliedAt()).isEqualTo(T0);
    }

    @Test
    void shortIdTakesFirstUuidBlock() {
        assertThat(Ids.shortId("1a2b3c4d-e5f6-7890-abcd-ef1234567890")).isEqualTo("1a2b3c4d");
        assertThat(Ids.shortId("plain")).isEqualTo("plain");
        assertThat(Ids.newId()).hasSize(36);
    }

    @Test
    void exceptionCarriesFailure() {
        ContractGuardException e = ContractGuardException.of(
                FailureCategory.DIRTY_REPOSITORY, "repo dirty", "commit or stash first");
        assertThat(e.failure().category()).isEqualTo(FailureCategory.DIRTY_REPOSITORY);
        assertThat(e.failure().mutationOccurred()).isFalse();
        assertThat(e.getMessage()).contains("DIRTY_REPOSITORY");
        ContractGuardException wrapped = new ContractGuardException(e.failure(), e);
        assertThat(wrapped.getCause()).isSameAs(e);
    }
}
