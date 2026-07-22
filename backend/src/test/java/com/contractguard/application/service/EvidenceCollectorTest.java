package com.contractguard.application.service;

import com.contractguard.application.port.RepositorySearchPort;
import com.contractguard.domain.ApiChange;
import com.contractguard.domain.ChangeType;
import com.contractguard.domain.Classification;
import com.contractguard.domain.ImpactEvidence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceCollectorTest {

    private final List<String> queries = new ArrayList<>();

    private final RepositorySearchPort recordingPort = (repositoryId, query, glob, maxResults) -> {
        queries.add(query);
        return List.of(new RepositorySearchPort.SearchMatch("src/A.java", 3, "match for " + query));
    };

    private final EvidenceCollector collector = new EvidenceCollector(recordingPort);

    private static ApiChange change(ChangeType type, String property, String oldValue, String newValue) {
        return new ApiChange("c-" + type, type, Classification.BREAKING, "GET", "/customers/{id}",
                "Customer", property, oldValue, newValue, "R", "{}", null);
    }

    @Test
    void endpointChangesSearchByTemplateFreePrefix() {
        collector.collect("demo", List.of(
                change(ChangeType.ENDPOINT_RENAMED, null, "/customers/{id}", "/v2/customers/{id}")));
        assertThat(queries).containsExactly("/customers/");
    }

    @Test
    void propertyRenameSearchesBothCasings() {
        collector.collect("demo", List.of(
                change(ChangeType.PROPERTY_RENAMED, "fullName", "fullName", "displayName")));
        assertThat(queries).containsExactly("fullName", "FullName");
    }

    @Test
    void enumRemovalSearchesTheValue() {
        collector.collect("demo", List.of(
                change(ChangeType.ENUM_VALUE_REMOVED, "status", "SUSPENDED", null)));
        assertThat(queries).containsExactly("SUSPENDED");
    }

    @Test
    void addedEndpointsProduceNoSearches() {
        List<ImpactEvidence> evidence = collector.collect("demo", List.of(
                change(ChangeType.ENDPOINT_ADDED, null, null, "/new")));
        assertThat(queries).isEmpty();
        assertThat(evidence).isEmpty();
    }

    @Test
    void duplicateLocationsAreDedupedPerChange() {
        // Both casings hit the same file/line: only one evidence record results.
        List<ImpactEvidence> evidence = collector.collect("demo", List.of(
                change(ChangeType.PROPERTY_RENAMED, "fullName", "fullName", "displayName")));
        assertThat(evidence).hasSize(1);
        ImpactEvidence item = evidence.get(0);
        assertThat(item.id()).isEqualTo("ev-1");
        assertThat(item.relativePath()).isEqualTo("src/A.java");
        assertThat(item.startLine()).isEqualTo(3);
        assertThat(item.contentHash()).hasSize(64);
    }

    @Test
    void unknownChangesSearchPathAndSchema() {
        collector.collect("demo", List.of(
                change(ChangeType.UNKNOWN_CHANGE, null, null, null)));
        assertThat(queries).containsExactly("/customers/", "Customer");
    }

    @Test
    void parameterAdditionSearchesTheParameterName() {
        collector.collect("demo", List.of(
                change(ChangeType.PARAMETER_ADDED, "verbose", null, "verbose")));
        assertThat(queries).containsExactly("verbose");
    }

    @Test
    void parameterRemovalSearchesTheParameterName() {
        collector.collect("demo", List.of(
                change(ChangeType.PARAMETER_REMOVED, "id", "id", null)));
        assertThat(queries).containsExactly("id");
    }

    @Test
    void requestBodySchemaChangeSearchesPathAndBothSchemaNames() {
        collector.collect("demo", List.of(
                change(ChangeType.REQUEST_BODY_SCHEMA_CHANGED, null, "BodyV1", "BodyV2")));
        assertThat(queries).containsExactly("/customers/", "BodyV1", "BodyV2");
    }

    @Test
    void responseStatusAdditionSearchesTheEndpointPath() {
        collector.collect("demo", List.of(
                change(ChangeType.RESPONSE_STATUS_ADDED, "429", null, "429")));
        assertThat(queries).containsExactly("/customers/");
    }
}
