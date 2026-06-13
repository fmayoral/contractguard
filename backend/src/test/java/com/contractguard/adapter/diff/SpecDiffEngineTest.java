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
    void parameterChangeSurfacesAsUnknownWithWarning() {
        SpecModel before = new SpecModel(Map.of(
                SpecModel.endpointKey("GET", "/a"),
                new SpecModel.Endpoint("GET", "/a", "A", Set.of("id"), null)), Map.of());
        SpecModel after = new SpecModel(Map.of(
                SpecModel.endpointKey("GET", "/a"),
                new SpecModel.Endpoint("GET", "/a", "A", Set.of("id", "verbose"), null)), Map.of());

        SpecDiffEngine.EngineResult result = engine.diff(before, after);

        assertThat(result.changes()).singleElement().satisfies(change -> {
            assertThat(change.type()).isEqualTo(ChangeType.UNKNOWN_CHANGE);
            assertThat(change.classification()).isEqualTo(Classification.UNKNOWN);
        });
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("GET /a"));
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
        return new SpecModel.Endpoint(method, path, responseSchema, Set.of(), null);
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
