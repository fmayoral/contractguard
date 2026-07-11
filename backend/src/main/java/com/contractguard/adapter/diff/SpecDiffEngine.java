package com.contractguard.adapter.diff;

import com.contractguard.domain.ApiChange;
import com.contractguard.domain.ChangeType;
import com.contractguard.domain.ClassificationPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic comparison of two {@link SpecModel}s. Produces normalised
 * {@link ApiChange}s with stable IDs, machine-readable evidence, and
 * deterministic rename pairing (ADR-0002). Anything outside the analysed
 * categories degrades to {@code UNKNOWN_CHANGE} plus a warning instead of
 * being silently dropped.
 */
public class SpecDiffEngine {

    private final ObjectMapper mapper = new ObjectMapper();

    public record EngineResult(List<ApiChange> changes, List<String> warnings) {
    }

    public EngineResult diff(SpecModel oldSpec, SpecModel newSpec) {
        List<ApiChange> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        diffEndpoints(oldSpec, newSpec, changes, warnings);
        diffSchemas(oldSpec, newSpec, changes, warnings);
        return new EngineResult(changes, warnings);
    }

    private void diffEndpoints(SpecModel oldSpec, SpecModel newSpec,
            List<ApiChange> changes, List<String> warnings) {
        Set<String> removedKeys = new TreeSet<>(oldSpec.endpoints().keySet());
        removedKeys.removeAll(newSpec.endpoints().keySet());
        Set<String> addedKeys = new TreeSet<>(newSpec.endpoints().keySet());
        addedKeys.removeAll(oldSpec.endpoints().keySet());

        Set<String> pairedAdded = new LinkedHashSet<>();
        for (String removedKey : removedKeys) {
            classifyRemovedEndpoint(oldSpec.endpoints().get(removedKey), newSpec,
                    addedKeys, pairedAdded, changes);
        }
        for (String addedKey : addedKeys) {
            if (pairedAdded.contains(addedKey)) {
                continue;
            }
            SpecModel.Endpoint added = newSpec.endpoints().get(addedKey);
            changes.add(endpointChange(ChangeType.ENDPOINT_ADDED, added.method(),
                    null, added.path(), added.responseSchema(),
                    evidence(e -> e.put("path", added.path()))));
        }
        diffSharedEndpoints(oldSpec, newSpec, changes, warnings);
    }

    /** Pairs a removed endpoint with a uniquely matching added one (rename) or reports removal. */
    private void classifyRemovedEndpoint(SpecModel.Endpoint removed, SpecModel newSpec,
            Set<String> addedKeys, Set<String> pairedAdded, List<ApiChange> changes) {
        List<SpecModel.Endpoint> candidates = addedKeys.stream()
                .filter(k -> !pairedAdded.contains(k))
                .map(k -> newSpec.endpoints().get(k))
                .filter(added -> added.method().equals(removed.method())
                        && Objects.equals(added.responseSchema(), removed.responseSchema())
                        && removed.responseSchema() != null)
                .toList();
        if (candidates.size() == 1) {
            SpecModel.Endpoint added = candidates.get(0);
            pairedAdded.add(SpecModel.endpointKey(added.method(), added.path()));
            changes.add(endpointChange(ChangeType.ENDPOINT_RENAMED, removed.method(),
                    removed.path(), added.path(), removed.responseSchema(),
                    evidence(e -> {
                        e.put("pairedBy", "method and response schema");
                        e.put("responseSchema", removed.responseSchema());
                        e.put("oldPath", removed.path());
                        e.put("newPath", added.path());
                    })));
        } else {
            changes.add(endpointChange(ChangeType.ENDPOINT_REMOVED, removed.method(),
                    removed.path(), null, removed.responseSchema(),
                    evidence(e -> {
                        e.put("path", removed.path());
                        e.put("renameCandidates", candidates.size());
                    })));
        }
    }

    private void diffSharedEndpoints(SpecModel oldSpec, SpecModel newSpec,
            List<ApiChange> changes, List<String> warnings) {
        for (String sharedKey : oldSpec.endpoints().keySet()) {
            SpecModel.Endpoint before = oldSpec.endpoints().get(sharedKey);
            SpecModel.Endpoint after = newSpec.endpoints().get(sharedKey);
            if (after == null) {
                continue;
            }
            if (!before.parameterNames().equals(after.parameterNames())
                    || !Objects.equals(before.requestBodySchema(), after.requestBodySchema())) {
                warnings.add("Operation-level change on %s is outside the analysed categories"
                        .formatted(sharedKey));
                changes.add(endpointChange(ChangeType.UNKNOWN_CHANGE, before.method(),
                        before.path(), after.path(), before.responseSchema(),
                        evidence(e -> {
                            e.put("detail", "parameters or request body changed");
                            e.put("endpoint", sharedKey);
                        })));
            }
        }
    }

    private void diffSchemas(SpecModel oldSpec, SpecModel newSpec,
            List<ApiChange> changes, List<String> warnings) {
        for (String schemaName : new TreeSet<>(oldSpec.schemas().keySet())) {
            SpecModel.SchemaShape before = oldSpec.schemas().get(schemaName);
            SpecModel.SchemaShape after = newSpec.schemas().get(schemaName);
            if (after == null) {
                warnings.add("Schema %s was removed; downstream analysis not performed".formatted(schemaName));
                changes.add(schemaChange(ChangeType.UNKNOWN_CHANGE, schemaName, null, schemaName, null,
                        evidence(e -> e.put("detail", "schema removed"))));
                continue;
            }
            diffProperties(schemaName, before, after, changes);
        }
        for (String schemaName : new TreeSet<>(newSpec.schemas().keySet())) {
            if (!oldSpec.schemas().containsKey(schemaName)) {
                warnings.add("Schema %s was added; not analysed for consumer impact".formatted(schemaName));
            }
        }
    }

    private void diffProperties(String schemaName, SpecModel.SchemaShape before,
            SpecModel.SchemaShape after, List<ApiChange> changes) {
        Set<String> removedProps = new TreeSet<>(before.properties().keySet());
        removedProps.removeAll(after.properties().keySet());
        Set<String> addedProps = new TreeSet<>(after.properties().keySet());
        addedProps.removeAll(before.properties().keySet());

        Set<String> pairedAdded = new LinkedHashSet<>();
        for (String removedName : removedProps) {
            classifyRemovedProperty(schemaName, removedName, before, after,
                    addedProps, pairedAdded, changes);
        }
        for (String addedName : addedProps) {
            if (pairedAdded.contains(addedName)) {
                continue;
            }
            boolean required = after.required().contains(addedName);
            changes.add(propertyAdded(schemaName, addedName, required,
                    evidence(e -> {
                        e.put("type", String.valueOf(after.properties().get(addedName).type()));
                        e.put("required", required);
                    })));
        }
        diffSharedProperties(schemaName, before, after, changes);
    }

    /** Pairs a removed property with a uniquely matching added one (rename) or reports removal. */
    private void classifyRemovedProperty(String schemaName, String removedName,
            SpecModel.SchemaShape before, SpecModel.SchemaShape after,
            Set<String> addedProps, Set<String> pairedAdded, List<ApiChange> changes) {
        SpecModel.PropertyShape removed = before.properties().get(removedName);
        boolean removedWasRequired = before.required().contains(removedName);
        List<String> candidates = addedProps.stream()
                .filter(name -> !pairedAdded.contains(name))
                .filter(name -> after.properties().get(name).sameTypeAs(removed))
                .filter(name -> after.required().contains(name) == removedWasRequired)
                .toList();
        if (candidates.size() == 1) {
            String addedName = candidates.get(0);
            pairedAdded.add(addedName);
            changes.add(schemaChange(ChangeType.PROPERTY_RENAMED, schemaName, removedName,
                    removedName, addedName,
                    evidence(e -> {
                        e.put("pairedBy", "identical type and required status");
                        e.put("type", String.valueOf(removed.type()));
                        e.put("required", removedWasRequired);
                    })));
        } else {
            changes.add(schemaChange(ChangeType.PROPERTY_REMOVED, schemaName, removedName,
                    removedName, null,
                    evidence(e -> {
                        e.put("renameCandidates", candidates.size());
                        e.put("required", removedWasRequired);
                    })));
        }
    }

    private void diffSharedProperties(String schemaName, SpecModel.SchemaShape before,
            SpecModel.SchemaShape after, List<ApiChange> changes) {
        for (String shared : new TreeSet<>(before.properties().keySet())) {
            SpecModel.PropertyShape oldProp = before.properties().get(shared);
            SpecModel.PropertyShape newProp = after.properties().get(shared);
            if (newProp == null) {
                continue;
            }
            if (!oldProp.sameTypeAs(newProp)) {
                changes.add(schemaChange(ChangeType.PROPERTY_TYPE_CHANGED, schemaName, shared,
                        typeLabel(oldProp), typeLabel(newProp),
                        evidence(e -> {
                            e.put("oldType", typeLabel(oldProp));
                            e.put("newType", typeLabel(newProp));
                        })));
            }
            diffEnumValues(schemaName, shared, oldProp, newProp, changes);
            boolean wasRequired = before.required().contains(shared);
            boolean isRequired = after.required().contains(shared);
            if (wasRequired != isRequired) {
                changes.add(schemaChange(ChangeType.PROPERTY_REQUIRED_CHANGED, schemaName, shared,
                        String.valueOf(wasRequired), String.valueOf(isRequired),
                        evidence(e -> {
                            e.put("wasRequired", wasRequired);
                            e.put("isRequired", isRequired);
                        })));
            }
        }
    }

    private void diffEnumValues(String schemaName, String property,
            SpecModel.PropertyShape oldProp, SpecModel.PropertyShape newProp, List<ApiChange> changes) {
        Set<String> removed = new TreeSet<>(oldProp.enumValues());
        removed.removeAll(newProp.enumValues());
        Set<String> added = new TreeSet<>(newProp.enumValues());
        added.removeAll(oldProp.enumValues());
        for (String value : removed) {
            changes.add(schemaChange(ChangeType.ENUM_VALUE_REMOVED, schemaName, property, value, null,
                    evidence(e -> {
                        e.put("value", value);
                        e.put("remainingValues", String.join(",", newProp.enumValues()));
                    })));
        }
        for (String value : added) {
            changes.add(schemaChange(ChangeType.ENUM_VALUE_ADDED, schemaName, property, null, value,
                    evidence(e -> e.put("value", value))));
        }
    }

    private ApiChange endpointChange(ChangeType type, String method, String oldPath,
            String newPath, String schema, String rawEvidence) {
        ClassificationPolicy.Result result = ClassificationPolicy.classify(type, false);
        return new ApiChange(changeId(type, method, oldPath, newPath, schema, null),
                type, result.classification(), method,
                oldPath != null ? oldPath : newPath, schema, null,
                oldPath, newPath, result.reason(), rawEvidence, null);
    }

    private ApiChange schemaChange(ChangeType type, String schema, String property,
            String oldValue, String newValue, String rawEvidence) {
        ClassificationPolicy.Result result = ClassificationPolicy.classify(type, false);
        return new ApiChange(changeId(type, null, null, null, schema, property + "|" + oldValue),
                type, result.classification(), null, null, schema, property,
                oldValue, newValue, result.reason(), rawEvidence, null);
    }

    private ApiChange propertyAdded(String schema, String property, boolean required, String rawEvidence) {
        ClassificationPolicy.Result result = ClassificationPolicy.classify(ChangeType.PROPERTY_ADDED, required);
        return new ApiChange(changeId(ChangeType.PROPERTY_ADDED, null, null, null, schema, property),
                ChangeType.PROPERTY_ADDED, result.classification(), null, null, schema, property,
                null, property, result.reason(), rawEvidence, null);
    }

    private static String typeLabel(SpecModel.PropertyShape shape) {
        return shape.format() == null ? String.valueOf(shape.type())
                : shape.type() + "(" + shape.format() + ")";
    }

    private String evidence(java.util.function.Consumer<ObjectNode> builder) {
        ObjectNode node = mapper.createObjectNode();
        builder.accept(node);
        return node.toString();
    }

    /** Stable content-derived ID so re-running a diff yields identical change IDs. */
    private String changeId(ChangeType type, String method, String oldPath, String newPath,
            String schema, String property) {
        String canonical = String.join("|", type.name(), String.valueOf(method), String.valueOf(oldPath),
                String.valueOf(newPath), String.valueOf(schema), String.valueOf(property));
        return "chg-" + sha256Hex(canonical).substring(0, 12);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
    }
}
