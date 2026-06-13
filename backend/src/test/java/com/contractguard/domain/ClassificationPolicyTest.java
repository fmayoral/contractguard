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

    @ParameterizedTest
    @EnumSource(ChangeType.class)
    void everyChangeTypeHasAMachineReadableReason(ChangeType type) {
        ClassificationPolicy.Result result = ClassificationPolicy.classify(type, false);
        assertThat(result.reason()).isNotBlank().matches("[A-Z0-9_]+");
    }
}
