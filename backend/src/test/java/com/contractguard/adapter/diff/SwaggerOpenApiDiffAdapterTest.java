package com.contractguard.adapter.diff;

import com.contractguard.application.port.OpenApiDiffPort;
import com.contractguard.domain.ApiChange;
import com.contractguard.domain.ChangeType;
import com.contractguard.domain.Classification;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the seeded demo scenario surfaces as exactly the four expected changes. */
class SwaggerOpenApiDiffAdapterTest {

    private static final Path OLD_SPEC = Path.of("../samples/openapi/customer-api-v1.yaml");
    private static final Path NEW_SPEC = Path.of("../samples/openapi/customer-api-v2.yaml");

    private final SwaggerOpenApiDiffAdapter adapter = new SwaggerOpenApiDiffAdapter();

    @Test
    void detectsExactlyTheFourSeededChanges() {
        OpenApiDiffPort.DiffResult result = adapter.diff(OLD_SPEC, NEW_SPEC);

        assertThat(result.changes()).hasSize(4);
        assertThat(result.changes())
                .extracting(ApiChange::type, ApiChange::classification)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                ChangeType.ENDPOINT_RENAMED, Classification.BREAKING),
                        org.assertj.core.groups.Tuple.tuple(
                                ChangeType.PROPERTY_RENAMED, Classification.BREAKING),
                        org.assertj.core.groups.Tuple.tuple(
                                ChangeType.ENUM_VALUE_REMOVED, Classification.BREAKING),
                        org.assertj.core.groups.Tuple.tuple(
                                ChangeType.PROPERTY_ADDED, Classification.NON_BREAKING));
    }

    @Test
    void endpointRenameCarriesOldAndNewPath() {
        ApiChange rename = changeOfType(ChangeType.ENDPOINT_RENAMED);
        assertThat(rename.method()).isEqualTo("GET");
        assertThat(rename.oldValue()).isEqualTo("/customers/{id}");
        assertThat(rename.newValue()).isEqualTo("/v2/customers/{id}");
        assertThat(rename.schema()).isEqualTo("Customer");
        assertThat(rename.rawEvidence()).contains("pairedBy");
    }

    @Test
    void propertyRenamePairsFullNameWithDisplayName() {
        ApiChange rename = changeOfType(ChangeType.PROPERTY_RENAMED);
        assertThat(rename.schema()).isEqualTo("Customer");
        assertThat(rename.oldValue()).isEqualTo("fullName");
        assertThat(rename.newValue()).isEqualTo("displayName");
    }

    @Test
    void suspendedEnumValueRemovalIsDetected() {
        ApiChange removal = changeOfType(ChangeType.ENUM_VALUE_REMOVED);
        assertThat(removal.schema()).isEqualTo("Customer");
        assertThat(removal.property()).isEqualTo("status");
        assertThat(removal.oldValue()).isEqualTo("SUSPENDED");
    }

    @Test
    void optionalPreferredLanguageAdditionIsNonBreaking() {
        ApiChange added = changeOfType(ChangeType.PROPERTY_ADDED);
        assertThat(added.property()).isEqualTo("preferredLanguage");
        assertThat(added.classification()).isEqualTo(Classification.NON_BREAKING);
    }

    @Test
    void resultCarriesSpecHashesAndStableChangeIds() {
        OpenApiDiffPort.DiffResult first = adapter.diff(OLD_SPEC, NEW_SPEC);
        OpenApiDiffPort.DiffResult second = adapter.diff(OLD_SPEC, NEW_SPEC);
        assertThat(first.oldSpecHash()).hasSize(64).isNotEqualTo(first.newSpecHash());
        assertThat(first.changes()).extracting(ApiChange::id)
                .containsExactlyElementsOf(second.changes().stream().map(ApiChange::id).toList());
    }

    private ApiChange changeOfType(ChangeType type) {
        return adapter.diff(OLD_SPEC, NEW_SPEC).changes().stream()
                .filter(c -> c.type() == type)
                .findFirst().orElseThrow();
    }
}
