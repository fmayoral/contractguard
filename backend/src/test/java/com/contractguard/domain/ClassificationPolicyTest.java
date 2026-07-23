package com.contractguard.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClassificationPolicyTest {

    @Test
    void removalsAndRenamesAreBreaking() {
        assertThat(ClassificationPolicy.classify(ChangeType.ENDPOINT_REMOVED, false).classification())
                .isEqualTo(Classification.BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.ENDPOINT_RENAMED, false).classification())
                .isEqualTo(Classification.BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.PROPERTY_REMOVED, false).classification())
                .isEqualTo(Classification.BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.PROPERTY_RENAMED, false).classification())
                .isEqualTo(Classification.BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.ENUM_VALUE_REMOVED, false).classification())
                .isEqualTo(Classification.BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.PROPERTY_TYPE_CHANGED, false).classification())
                .isEqualTo(Classification.BREAKING);
    }

    @Test
    void additionsAreNonBreakingUnlessRequired() {
        assertThat(ClassificationPolicy.classify(ChangeType.PROPERTY_ADDED, false).classification())
                .isEqualTo(Classification.NON_BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.PROPERTY_ADDED, true).classification())
                .isEqualTo(Classification.POTENTIALLY_BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.ENDPOINT_ADDED, false).classification())
                .isEqualTo(Classification.NON_BREAKING);
    }

    @Test
    void uncertainCategoriesArePotentiallyBreakingOrUnknown() {
        assertThat(ClassificationPolicy.classify(ChangeType.ENUM_VALUE_ADDED, false).classification())
                .isEqualTo(Classification.POTENTIALLY_BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.PROPERTY_REQUIRED_CHANGED, false).classification())
                .isEqualTo(Classification.POTENTIALLY_BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.UNKNOWN_CHANGE, false).classification())
                .isEqualTo(Classification.UNKNOWN);
    }

    @Test
    void expandedTaxonomyFollowsTheSameConventionAsProperties() {
        // Removals/type changes: BREAKING, mirroring PROPERTY_REMOVED/PROPERTY_TYPE_CHANGED.
        assertThat(ClassificationPolicy.classify(ChangeType.PARAMETER_REMOVED, false).classification())
                .isEqualTo(Classification.BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.PARAMETER_TYPE_CHANGED, false).classification())
                .isEqualTo(Classification.BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.REQUEST_BODY_REMOVED, false).classification())
                .isEqualTo(Classification.BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.REQUEST_BODY_SCHEMA_CHANGED, false).classification())
                .isEqualTo(Classification.BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.RESPONSE_STATUS_REMOVED, false).classification())
                .isEqualTo(Classification.BREAKING);

        // Additions: NON_BREAKING unless required, mirroring PROPERTY_ADDED.
        assertThat(ClassificationPolicy.classify(ChangeType.PARAMETER_ADDED, false).classification())
                .isEqualTo(Classification.NON_BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.PARAMETER_ADDED, true).classification())
                .isEqualTo(Classification.POTENTIALLY_BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.REQUEST_BODY_ADDED, false).classification())
                .isEqualTo(Classification.NON_BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.REQUEST_BODY_ADDED, true).classification())
                .isEqualTo(Classification.POTENTIALLY_BREAKING);

        // Required-flag drift: POTENTIALLY_BREAKING, mirroring PROPERTY_REQUIRED_CHANGED.
        assertThat(ClassificationPolicy.classify(ChangeType.PARAMETER_REQUIRED_CHANGED, false).classification())
                .isEqualTo(Classification.POTENTIALLY_BREAKING);

        // Response status codes mirror enum values: adding one is the risky direction
        // (exhaustive switches miss it), removing one is the safe-to-detect direction.
        assertThat(ClassificationPolicy.classify(ChangeType.RESPONSE_STATUS_ADDED, false).classification())
                .isEqualTo(Classification.POTENTIALLY_BREAKING);
    }

    @Test
    void secondTaxonomyRoundFollowsTheSameConventions() {
        // Nullable flips either way are surfaced, mirroring PROPERTY_REQUIRED_CHANGED's symmetric rule.
        assertThat(ClassificationPolicy.classify(ChangeType.PROPERTY_NULLABLE_CHANGED, false).classification())
                .isEqualTo(Classification.POTENTIALLY_BREAKING);

        // Only ever produced for the tightening direction -- always potentially breaking.
        assertThat(ClassificationPolicy.classify(ChangeType.PROPERTY_CONSTRAINT_TIGHTENED, false).classification())
                .isEqualTo(Classification.POTENTIALLY_BREAKING);

        // Content types: removal is BREAKING (existing callers using it are rejected), addition is
        // NON_BREAKING (an alternative representation, existing callers unaffected).
        assertThat(ClassificationPolicy.classify(ChangeType.REQUEST_BODY_CONTENT_TYPE_REMOVED, false).classification())
                .isEqualTo(Classification.BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.REQUEST_BODY_CONTENT_TYPE_ADDED, false).classification())
                .isEqualTo(Classification.NON_BREAKING);

        // Security requirements mirror the required-addition pattern: a newly required credential
        // is POTENTIALLY_BREAKING, a dropped one is NON_BREAKING (existing credentialed callers are fine).
        assertThat(ClassificationPolicy.classify(ChangeType.SECURITY_REQUIREMENT_ADDED, false).classification())
                .isEqualTo(Classification.POTENTIALLY_BREAKING);
        assertThat(ClassificationPolicy.classify(ChangeType.SECURITY_REQUIREMENT_REMOVED, false).classification())
                .isEqualTo(Classification.NON_BREAKING);
    }

    @ParameterizedTest
    @EnumSource(ChangeType.class)
    void everyChangeTypeHasAMachineReadableReason(ChangeType type) {
        ClassificationPolicy.Result result = ClassificationPolicy.classify(type, false);
        assertThat(result.reason()).isNotBlank().matches("[A-Z0-9_]+");
    }
}
