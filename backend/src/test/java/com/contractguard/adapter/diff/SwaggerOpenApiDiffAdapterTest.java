package com.contractguard.adapter.diff;

import com.contractguard.application.port.OpenApiDiffPort;
import com.contractguard.domain.ApiChange;
import com.contractguard.domain.ChangeType;
import com.contractguard.domain.Classification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    /**
     * End-to-end check of ADR-0014's new categories through the real parser and diff engine
     * together (not hand-built {@link SpecModel} objects, unlike {@code SpecDiffEngineTest}) --
     * confirms {@link OpenApiSpecReader}'s parameter/request-body/response-status extraction and
     * {@link SpecDiffEngine}'s comparison of them agree with each other on real YAML.
     */
    @Test
    void expandedTaxonomyIsDetectedThroughTheRealParserAndEngineTogether(@TempDir Path dir) throws IOException {
        Path oldSpec = Files.writeString(dir.resolve("old.yaml"), """
                openapi: 3.0.3
                info: {title: Orders, version: "1"}
                paths:
                  /orders:
                    post:
                      parameters:
                        - name: id
                          in: query
                          required: true
                          schema: {type: string}
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/OrderRequestV1'}
                      responses:
                        '201': {description: created}
                        '409': {description: conflict}
                components:
                  schemas:
                    OrderRequestV1:
                      type: object
                """);
        // OrderRequestV1 stays declared below (unused) so only the endpoint's schema *reference*
        // changes -- a fully removed schema is a separate, unrelated signal (diffSchemas' own
        // UNKNOWN_CHANGE fallback) this test isn't about.
        Path newSpec = Files.writeString(dir.resolve("new.yaml"), """
                openapi: 3.0.3
                info: {title: Orders, version: "2"}
                paths:
                  /orders:
                    post:
                      parameters:
                        - name: id
                          in: query
                          required: false
                          schema: {type: string}
                        - name: idempotencyKey
                          in: header
                          required: true
                          schema: {type: string}
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/OrderRequestV2'}
                      responses:
                        '201': {description: created}
                        '429': {description: throttled}
                components:
                  schemas:
                    OrderRequestV1:
                      type: object
                    OrderRequestV2:
                      type: object
                """);

        List<ApiChange> changes = adapter.diff(oldSpec, newSpec).changes();

        assertThat(changes).extracting(ApiChange::type, ApiChange::classification)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                ChangeType.PARAMETER_ADDED, Classification.POTENTIALLY_BREAKING),
                        org.assertj.core.groups.Tuple.tuple(
                                ChangeType.PARAMETER_REQUIRED_CHANGED, Classification.POTENTIALLY_BREAKING),
                        org.assertj.core.groups.Tuple.tuple(
                                ChangeType.REQUEST_BODY_SCHEMA_CHANGED, Classification.BREAKING),
                        org.assertj.core.groups.Tuple.tuple(
                                ChangeType.RESPONSE_STATUS_ADDED, Classification.POTENTIALLY_BREAKING),
                        org.assertj.core.groups.Tuple.tuple(
                                ChangeType.RESPONSE_STATUS_REMOVED, Classification.BREAKING));

        ApiChange schemaChange = changes.stream()
                .filter(c -> c.type() == ChangeType.REQUEST_BODY_SCHEMA_CHANGED)
                .findFirst().orElseThrow();
        assertThat(schemaChange.oldValue()).isEqualTo("OrderRequestV1");
        assertThat(schemaChange.newValue()).isEqualTo("OrderRequestV2");
    }

    /** Covers the second FR-034 round (ADR-0014 addendum): nullability, constraint tightening, content types, security. */
    @Test
    void secondRoundOfTheExpandedTaxonomyIsDetectedThroughTheRealParserAndEngineTogether(@TempDir Path dir)
            throws IOException {
        Path oldSpec = Files.writeString(dir.resolve("old.yaml"), """
                openapi: 3.0.3
                info: {title: Orders, version: "1"}
                paths:
                  /orders:
                    post:
                      security:
                        - apiKey: []
                      requestBody:
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/OrderRequest'}
                      responses:
                        '201': {description: created}
                components:
                  schemas:
                    OrderRequest:
                      type: object
                      properties:
                        note:
                          type: string
                          maxLength: 200
                """);
        Path newSpec = Files.writeString(dir.resolve("new.yaml"), """
                openapi: 3.0.3
                info: {title: Orders, version: "2"}
                paths:
                  /orders:
                    post:
                      security:
                        - oauth2: [write]
                      requestBody:
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/OrderRequest'}
                          application/xml:
                            schema: {$ref: '#/components/schemas/OrderRequest'}
                      responses:
                        '201': {description: created}
                components:
                  schemas:
                    OrderRequest:
                      type: object
                      properties:
                        note:
                          type: string
                          nullable: true
                          maxLength: 50
                """);

        List<ApiChange> changes = adapter.diff(oldSpec, newSpec).changes();

        assertThat(changes).extracting(ApiChange::type, ApiChange::classification)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(
                                ChangeType.PROPERTY_NULLABLE_CHANGED, Classification.POTENTIALLY_BREAKING),
                        org.assertj.core.groups.Tuple.tuple(
                                ChangeType.PROPERTY_CONSTRAINT_TIGHTENED, Classification.POTENTIALLY_BREAKING),
                        org.assertj.core.groups.Tuple.tuple(
                                ChangeType.REQUEST_BODY_CONTENT_TYPE_ADDED, Classification.NON_BREAKING),
                        org.assertj.core.groups.Tuple.tuple(
                                ChangeType.SECURITY_REQUIREMENT_ADDED, Classification.POTENTIALLY_BREAKING),
                        org.assertj.core.groups.Tuple.tuple(
                                ChangeType.SECURITY_REQUIREMENT_REMOVED, Classification.NON_BREAKING));

        ApiChange tightened = changes.stream()
                .filter(c -> c.type() == ChangeType.PROPERTY_CONSTRAINT_TIGHTENED)
                .findFirst().orElseThrow();
        assertThat(tightened.newValue()).isEqualTo("maxLength 200->50");
    }
}
