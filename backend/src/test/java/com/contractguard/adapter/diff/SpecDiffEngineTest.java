package com.contractguard.adapter.diff;

import com.contractguard.domain.ApiChange;
import com.contractguard.domain.ChangeType;
import com.contractguard.domain.Classification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SpecDiffEngineTest {

    private final SpecDiffEngine engine = new SpecDiffEngine();

    @Test
    void ambiguousPropertyRenameDegradesToRemovedPlusAdded() {
        // Two same-typed optional additions: pairing would be a guess, so none happens.
        SpecModel before = schemaOnly("Thing", Map.of(
                "alpha", stringProp()), Set.of());
        SpecModel after = schemaOnly("Thing", Map.of(
                "beta", stringProp(),
                "gamma", stringProp()), Set.of());

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).extracting(ApiChange::type).containsExactlyInAnyOrder(
                ChangeType.PROPERTY_REMOVED, ChangeType.PROPERTY_ADDED, ChangeType.PROPERTY_ADDED);
    }

    @Test
    void requiredStatusBreaksRenameTies() {
        // One required and one optional string added; the required removal pairs with the required addition.
        SpecModel before = schemaOnly("Thing", Map.of("alpha", stringProp()), Set.of("alpha"));
        SpecModel after = schemaOnly("Thing", Map.of(
                "beta", stringProp(),
                "gamma", stringProp()), Set.of("beta"));

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).extracting(ApiChange::type).containsExactlyInAnyOrder(
                ChangeType.PROPERTY_RENAMED, ChangeType.PROPERTY_ADDED);
        ApiChange rename = changes.stream().filter(c -> c.type() == ChangeType.PROPERTY_RENAMED)
                .findFirst().orElseThrow();
        assertThat(rename.newValue()).isEqualTo("beta");
    }

    @Test
    void typeMismatchPreventsRenamePairing() {
        SpecModel before = schemaOnly("Thing", Map.of("count", intProp()), Set.of());
        SpecModel after = schemaOnly("Thing", Map.of("label", stringProp()), Set.of());

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).extracting(ApiChange::type).containsExactlyInAnyOrder(
                ChangeType.PROPERTY_REMOVED, ChangeType.PROPERTY_ADDED);
    }

    @Test
    void propertyTypeChangeIsBreaking() {
        SpecModel before = schemaOnly("Thing", Map.of("value", stringProp()), Set.of());
        SpecModel after = schemaOnly("Thing", Map.of("value", intProp()), Set.of());

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.PROPERTY_TYPE_CHANGED);
            assertThat(change.classification()).isEqualTo(Classification.BREAKING);
        });
    }

    @Test
    void requiredFlagChangeIsPotentiallyBreaking() {
        SpecModel before = schemaOnly("Thing", Map.of("value", stringProp()), Set.of());
        SpecModel after = schemaOnly("Thing", Map.of("value", stringProp()), Set.of("value"));

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.PROPERTY_REQUIRED_CHANGED);
            assertThat(change.classification()).isEqualTo(Classification.POTENTIALLY_BREAKING);
        });
    }

    @Test
    void enumAdditionIsPotentiallyBreaking() {
        SpecModel before = schemaOnly("Thing",
                Map.of("state", enumProp(List.of("A", "B"))), Set.of());
        SpecModel after = schemaOnly("Thing",
                Map.of("state", enumProp(List.of("A", "B", "C"))), Set.of());

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.ENUM_VALUE_ADDED);
            assertThat(change.newValue()).isEqualTo("C");
        });
    }

    @Test
    void endpointRemovalWithoutCandidateIsBreakingRemoval() {
        SpecModel before = new SpecModel(Map.of(
                SpecModel.endpointKey("GET", "/a"), endpoint("GET", "/a", "A")), Map.of());
        SpecModel after = new SpecModel(Map.of(), Map.of());

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.ENDPOINT_REMOVED);
            assertThat(change.classification()).isEqualTo(Classification.BREAKING);
        });
    }

    @Test
    void endpointRenameRequiresMatchingResponseSchema() {
        SpecModel before = new SpecModel(Map.of(
                SpecModel.endpointKey("GET", "/a"), endpoint("GET", "/a", "A")), Map.of());
        SpecModel after = new SpecModel(Map.of(
                SpecModel.endpointKey("GET", "/b"), endpoint("GET", "/b", "B")), Map.of());

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).extracting(ApiChange::type).containsExactlyInAnyOrder(
                ChangeType.ENDPOINT_REMOVED, ChangeType.ENDPOINT_ADDED);
    }

    @Test
    void requiredParameterAdditionIsPotentiallyBreaking() {
        SpecModel before = withEndpoint(endpointWithParams("GET", "/a", Map.of()));
        SpecModel after = withEndpoint(endpointWithParams("GET", "/a",
                Map.of("verbose", param("query", true, "boolean"))));

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.PARAMETER_ADDED);
            assertThat(change.classification()).isEqualTo(Classification.POTENTIALLY_BREAKING);
            assertThat(change.property()).isEqualTo("verbose");
        });
    }

    @Test
    void optionalParameterAdditionIsNonBreaking() {
        SpecModel before = withEndpoint(endpointWithParams("GET", "/a", Map.of()));
        SpecModel after = withEndpoint(endpointWithParams("GET", "/a",
                Map.of("verbose", param("query", false, "boolean"))));

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).singleElement().satisfies(change ->
                assertThat(change.classification()).isEqualTo(Classification.NON_BREAKING));
    }

    @Test
    void parameterRemovalIsBreaking() {
        SpecModel before = withEndpoint(endpointWithParams("GET", "/a",
                Map.of("id", param("path", true, "string"))));
        SpecModel after = withEndpoint(endpointWithParams("GET", "/a", Map.of()));

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.PARAMETER_REMOVED);
            assertThat(change.classification()).isEqualTo(Classification.BREAKING);
        });
    }

    @Test
    void parameterTypeChangeIsBreaking() {
        SpecModel before = withEndpoint(endpointWithParams("GET", "/a",
                Map.of("id", param("path", true, "string"))));
        SpecModel after = withEndpoint(endpointWithParams("GET", "/a",
                Map.of("id", param("path", true, "integer"))));

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.PARAMETER_TYPE_CHANGED);
            assertThat(change.classification()).isEqualTo(Classification.BREAKING);
        });
    }

    @Test
    void parameterRequiredFlagChangeIsPotentiallyBreaking() {
        SpecModel before = withEndpoint(endpointWithParams("GET", "/a",
                Map.of("id", param("path", false, "string"))));
        SpecModel after = withEndpoint(endpointWithParams("GET", "/a",
                Map.of("id", param("path", true, "string"))));

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.PARAMETER_REQUIRED_CHANGED);
            assertThat(change.classification()).isEqualTo(Classification.POTENTIALLY_BREAKING);
        });
    }

    @Test
    void requiredRequestBodyAdditionIsPotentiallyBreaking() {
        SpecModel before = withEndpoint(endpointWithRequestBody("POST", "/a", null, false));
        SpecModel after = withEndpoint(endpointWithRequestBody("POST", "/a", "Body", true));

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.REQUEST_BODY_ADDED);
            assertThat(change.classification()).isEqualTo(Classification.POTENTIALLY_BREAKING);
            assertThat(change.newValue()).isEqualTo("Body");
        });
    }

    @Test
    void requestBodyRemovalIsBreaking() {
        SpecModel before = withEndpoint(endpointWithRequestBody("POST", "/a", "Body", true));
        SpecModel after = withEndpoint(endpointWithRequestBody("POST", "/a", null, false));

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.REQUEST_BODY_REMOVED);
            assertThat(change.classification()).isEqualTo(Classification.BREAKING);
        });
    }

    @Test
    void requestBodySchemaChangeIsBreaking() {
        SpecModel before = withEndpoint(endpointWithRequestBody("POST", "/a", "BodyV1", true));
        SpecModel after = withEndpoint(endpointWithRequestBody("POST", "/a", "BodyV2", true));

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.REQUEST_BODY_SCHEMA_CHANGED);
            assertThat(change.classification()).isEqualTo(Classification.BREAKING);
            assertThat(change.oldValue()).isEqualTo("BodyV1");
            assertThat(change.newValue()).isEqualTo("BodyV2");
        });
    }

    @Test
    void responseStatusAdditionIsPotentiallyBreaking() {
        SpecModel before = withEndpoint(endpointWithStatusCodes("GET", "/a", Set.of("200")));
        SpecModel after = withEndpoint(endpointWithStatusCodes("GET", "/a", Set.of("200", "429")));

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.RESPONSE_STATUS_ADDED);
            assertThat(change.classification()).isEqualTo(Classification.POTENTIALLY_BREAKING);
            assertThat(change.property()).isEqualTo("429");
        });
    }

    @Test
    void responseStatusRemovalIsBreaking() {
        SpecModel before = withEndpoint(endpointWithStatusCodes("GET", "/a", Set.of("200", "404")));
        SpecModel after = withEndpoint(endpointWithStatusCodes("GET", "/a", Set.of("200")));

        List<ApiChange> changes = engine.diff(before, after).changes();

        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.RESPONSE_STATUS_REMOVED);
            assertThat(change.classification()).isEqualTo(Classification.BREAKING);
            assertThat(change.property()).isEqualTo("404");
        });
    }

    @Test
    void schemaRemovalSurfacesAsUnknownWithWarning() {
        SpecModel before = schemaOnly("Gone", Map.of("x", stringProp()), Set.of());
        SpecModel after = new SpecModel(Map.of(), Map.of());

        SpecDiffEngine.EngineResult result = engine.diff(before, after);

        assertThat(result.changes()).extracting(ApiChange::type)
                .containsExactly(ChangeType.UNKNOWN_CHANGE);
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("Gone"));
    }

    @Test
    void identicalSpecsProduceNoChanges() {
        SpecModel spec = schemaOnly("Thing", Map.of("value", stringProp()), Set.of("value"));
        SpecDiffEngine.EngineResult result = engine.diff(spec, spec);
        assertThat(result.changes()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    private static SpecModel schemaOnly(String name, Map<String, SpecModel.PropertyShape> props,
            Set<String> required) {
        return new SpecModel(Map.of(), Map.of(name, new SpecModel.SchemaShape(props, required)));
    }

    private static SpecModel.Endpoint endpoint(String method, String path, String responseSchema) {
        return new SpecModel.Endpoint(method, path, responseSchema, Map.of(), null, false, Set.of());
    }

    private static SpecModel withEndpoint(SpecModel.Endpoint endpoint) {
        return new SpecModel(Map.of(SpecModel.endpointKey(endpoint.method(), endpoint.path()), endpoint), Map.of());
    }

    private static SpecModel.Endpoint endpointWithParams(String method, String path,
            Map<String, SpecModel.ParameterShape> parameters) {
        return new SpecModel.Endpoint(method, path, null, parameters, null, false, Set.of());
    }

    private static SpecModel.ParameterShape param(String location, boolean required, String type) {
        return new SpecModel.ParameterShape(location, required, new SpecModel.PropertyShape(type, null, List.of()));
    }

    private static SpecModel.Endpoint endpointWithRequestBody(String method, String path,
            String schema, boolean required) {
        return new SpecModel.Endpoint(method, path, null, Map.of(), schema, required, Set.of());
    }

    private static SpecModel.Endpoint endpointWithStatusCodes(String method, String path, Set<String> codes) {
        return new SpecModel.Endpoint(method, path, null, Map.of(), null, false, codes);
    }

    private static SpecModel.PropertyShape stringProp() {
        return new SpecModel.PropertyShape("string", null, List.of());
    }

    private static SpecModel.PropertyShape intProp() {
        return new SpecModel.PropertyShape("integer", "int32", List.of());
    }

    private static SpecModel.PropertyShape enumProp(List<String> values) {
        return new SpecModel.PropertyShape("string", null, values);
    }
}
