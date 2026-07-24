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

    // Evidence JSON field names, reused across several parameter/request-body change types below.
    private static final String EVIDENCE_FIELD_PARAMETER = "parameter";
    private static final String EVIDENCE_FIELD_REQUIRED = "required";

    private final ObjectMapper mapper = new ObjectMapper();

    public record EngineResult(List<ApiChange> changes, List<String> warnings) {
    }

    public EngineResult diff(SpecModel oldSpec, SpecModel newSpec) {
        List<ApiChange> changes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        diffEndpoints(oldSpec, newSpec, changes);
        diffSchemas(oldSpec, newSpec, changes, warnings);
        return new EngineResult(changes, warnings);
    }

    private void diffEndpoints(SpecModel oldSpec, SpecModel newSpec, List<ApiChange> changes) {
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
        diffSharedEndpoints(oldSpec, newSpec, changes);
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

    private void diffSharedEndpoints(SpecModel oldSpec, SpecModel newSpec, List<ApiChange> changes) {
        for (String sharedKey : oldSpec.endpoints().keySet()) {
            SpecModel.Endpoint before = oldSpec.endpoints().get(sharedKey);
            SpecModel.Endpoint after = newSpec.endpoints().get(sharedKey);
            if (after == null) {
                continue;
            }
            diffParameters(before, after, changes);
            diffRequestBody(before, after, changes);
            diffResponseStatusCodes(before, after, changes);
            diffRequestBodyContentTypes(before, after, changes);
            diffSecuritySchemes(before, after, changes);
        }
    }

    /** No rename pairing (unlike endpoints/properties): a renamed parameter surfaces as remove+add. */
    private void diffParameters(SpecModel.Endpoint before, SpecModel.Endpoint after, List<ApiChange> changes) {
        Set<String> removedNames = new TreeSet<>(before.parameters().keySet());
        removedNames.removeAll(after.parameters().keySet());
        Set<String> addedNames = new TreeSet<>(after.parameters().keySet());
        addedNames.removeAll(before.parameters().keySet());

        for (String name : removedNames) {
            SpecModel.ParameterShape removed = before.parameters().get(name);
            changes.add(endpointDetailChange(ChangeType.PARAMETER_REMOVED, before.method(), before.path(),
                    name, name, null, false,
                    evidence(e -> {
                        e.put(EVIDENCE_FIELD_PARAMETER, name);
                        e.put("in", removed.location());
                    })));
        }
        for (String name : addedNames) {
            SpecModel.ParameterShape added = after.parameters().get(name);
            changes.add(endpointDetailChange(ChangeType.PARAMETER_ADDED, after.method(), after.path(),
                    name, null, name, added.required(),
                    evidence(e -> {
                        e.put(EVIDENCE_FIELD_PARAMETER, name);
                        e.put("in", added.location());
                        e.put(EVIDENCE_FIELD_REQUIRED, added.required());
                    })));
        }
        diffSharedParameters(before, after, changes);
    }

    private void diffSharedParameters(SpecModel.Endpoint before, SpecModel.Endpoint after, List<ApiChange> changes) {
        for (String name : new TreeSet<>(before.parameters().keySet())) {
            SpecModel.ParameterShape oldParam = before.parameters().get(name);
            SpecModel.ParameterShape newParam = after.parameters().get(name);
            if (newParam == null) {
                continue;
            }
            if (!oldParam.sameTypeAs(newParam)) {
                changes.add(endpointDetailChange(ChangeType.PARAMETER_TYPE_CHANGED, before.method(), before.path(),
                        name, typeLabel(oldParam.shape()), typeLabel(newParam.shape()), false,
                        evidence(e -> {
                            e.put(EVIDENCE_FIELD_PARAMETER, name);
                            e.put("oldType", typeLabel(oldParam.shape()));
                            e.put("newType", typeLabel(newParam.shape()));
                        })));
            }
            if (oldParam.required() != newParam.required()) {
                changes.add(endpointDetailChange(ChangeType.PARAMETER_REQUIRED_CHANGED, before.method(), before.path(),
                        name, String.valueOf(oldParam.required()), String.valueOf(newParam.required()), false,
                        evidence(e -> {
                            e.put(EVIDENCE_FIELD_PARAMETER, name);
                            e.put("wasRequired", oldParam.required());
                            e.put("isRequired", newParam.required());
                        })));
            }
        }
    }

    /** Added/removed/changed are distinguished since consumer impact differs (ADR-0014). */
    private void diffRequestBody(SpecModel.Endpoint before, SpecModel.Endpoint after, List<ApiChange> changes) {
        String oldSchema = before.requestBodySchema();
        String newSchema = after.requestBodySchema();
        if (Objects.equals(oldSchema, newSchema)) {
            return;
        }
        if (oldSchema == null) {
            changes.add(endpointDetailChange(ChangeType.REQUEST_BODY_ADDED, after.method(), after.path(),
                    null, null, newSchema, after.requestBodyRequired(),
                    evidence(e -> {
                        e.put("schema", newSchema);
                        e.put(EVIDENCE_FIELD_REQUIRED, after.requestBodyRequired());
                    })));
        } else if (newSchema == null) {
            changes.add(endpointDetailChange(ChangeType.REQUEST_BODY_REMOVED, before.method(), before.path(),
                    null, oldSchema, null, false,
                    evidence(e -> e.put("schema", oldSchema))));
        } else {
            changes.add(endpointDetailChange(ChangeType.REQUEST_BODY_SCHEMA_CHANGED, before.method(), before.path(),
                    null, oldSchema, newSchema, false,
                    evidence(e -> {
                        e.put("oldSchema", oldSchema);
                        e.put("newSchema", newSchema);
                    })));
        }
    }

    private void diffResponseStatusCodes(SpecModel.Endpoint before, SpecModel.Endpoint after, List<ApiChange> changes) {
        Set<String> removed = new TreeSet<>(before.responseStatusCodes());
        removed.removeAll(after.responseStatusCodes());
        Set<String> added = new TreeSet<>(after.responseStatusCodes());
        added.removeAll(before.responseStatusCodes());

        for (String status : removed) {
            changes.add(endpointDetailChange(ChangeType.RESPONSE_STATUS_REMOVED, before.method(), before.path(),
                    status, status, null, false, evidence(e -> e.put("status", status))));
        }
        for (String status : added) {
            changes.add(endpointDetailChange(ChangeType.RESPONSE_STATUS_ADDED, after.method(), after.path(),
                    status, null, status, false, evidence(e -> e.put("status", status))));
        }
    }

    /** Scoped to the request body's media types only; per-response-status content types are not tracked (ADR-0014). */
    private void diffRequestBodyContentTypes(SpecModel.Endpoint before, SpecModel.Endpoint after, List<ApiChange> changes) {
        Set<String> removed = new TreeSet<>(before.requestBodyContentTypes());
        removed.removeAll(after.requestBodyContentTypes());
        Set<String> added = new TreeSet<>(after.requestBodyContentTypes());
        added.removeAll(before.requestBodyContentTypes());

        for (String contentType : removed) {
            changes.add(endpointDetailChange(ChangeType.REQUEST_BODY_CONTENT_TYPE_REMOVED, before.method(),
                    before.path(), contentType, contentType, null, false,
                    evidence(e -> e.put("contentType", contentType))));
        }
        for (String contentType : added) {
            changes.add(endpointDetailChange(ChangeType.REQUEST_BODY_CONTENT_TYPE_ADDED, after.method(),
                    after.path(), contentType, null, contentType, false,
                    evidence(e -> e.put("contentType", contentType))));
        }
    }

    private void diffSecuritySchemes(SpecModel.Endpoint before, SpecModel.Endpoint after, List<ApiChange> changes) {
        Set<String> removed = new TreeSet<>(before.securitySchemes());
        removed.removeAll(after.securitySchemes());
        Set<String> added = new TreeSet<>(after.securitySchemes());
        added.removeAll(before.securitySchemes());

        for (String scheme : removed) {
            changes.add(endpointDetailChange(ChangeType.SECURITY_REQUIREMENT_REMOVED, before.method(), before.path(),
                    scheme, scheme, null, false, evidence(e -> e.put("scheme", scheme))));
        }
        for (String scheme : added) {
            changes.add(endpointDetailChange(ChangeType.SECURITY_REQUIREMENT_ADDED, after.method(), after.path(),
                    scheme, null, scheme, false, evidence(e -> e.put("scheme", scheme))));
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
                        e.put(EVIDENCE_FIELD_REQUIRED, required);
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
                        e.put(EVIDENCE_FIELD_REQUIRED, removedWasRequired);
                    })));
        } else {
            changes.add(schemaChange(ChangeType.PROPERTY_REMOVED, schemaName, removedName,
                    removedName, null,
                    evidence(e -> {
                        e.put("renameCandidates", candidates.size());
                        e.put(EVIDENCE_FIELD_REQUIRED, removedWasRequired);
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
            if (oldProp.nullable() != newProp.nullable()) {
                changes.add(schemaChange(ChangeType.PROPERTY_NULLABLE_CHANGED, schemaName, shared,
                        String.valueOf(oldProp.nullable()), String.valueOf(newProp.nullable()),
                        evidence(e -> {
                            e.put("wasNullable", oldProp.nullable());
                            e.put("isNullable", newProp.nullable());
                        })));
            }
            List<String> tightened = tighteningDetails(oldProp.constraints(), newProp.constraints());
            if (!tightened.isEmpty()) {
                String detail = String.join(", ", tightened);
                changes.add(schemaChange(ChangeType.PROPERTY_CONSTRAINT_TIGHTENED, schemaName, shared,
                        null, detail, evidence(e -> e.put("detail", detail))));
            }
        }
    }

    /**
     * Only the tightening direction is reported (a shrinking allowed range can reject previously
     * valid values); loosening is not surfaced since it never breaks an existing valid caller.
     * Pattern/regex tightening is not modelled -- regex containment is undecidable in general.
     */
    private static List<String> tighteningDetails(SpecModel.Constraints before, SpecModel.Constraints after) {
        List<String> details = new ArrayList<>();
        if (tightenedUpperBound(before.maxLength(), after.maxLength())) {
            details.add("maxLength %s->%s".formatted(before.maxLength(), after.maxLength()));
        }
        if (tightenedLowerBound(before.minLength(), after.minLength())) {
            details.add("minLength %s->%s".formatted(before.minLength(), after.minLength()));
        }
        if (tightenedUpperBound(before.maximum(), after.maximum())) {
            details.add("maximum %s->%s".formatted(before.maximum(), after.maximum()));
        }
        if (tightenedLowerBound(before.minimum(), after.minimum())) {
            details.add("minimum %s->%s".formatted(before.minimum(), after.minimum()));
        }
        return details;
    }

    /** An upper bound ({@code maxLength}/{@code maximum}) tightens when it decreases (or is newly set). */
    private static boolean tightenedUpperBound(Number before, Number after) {
        return after != null && (before == null || after.doubleValue() < before.doubleValue());
    }

    /** A lower bound ({@code minLength}/{@code minimum}) tightens when it increases (or is newly set). */
    private static boolean tightenedLowerBound(Number before, Number after) {
        return after != null && (before == null || after.doubleValue() > before.doubleValue());
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

    /**
     * Builds a parameter/request-body/response-status change on a shared (never renamed) endpoint.
     * {@code detail} carries the parameter name or status code -- null for request-body changes,
     * which are endpoint-scoped rather than named.
     */
    private ApiChange endpointDetailChange(ChangeType type, String method, String path, String detail,
            String oldValue, String newValue, boolean affectsRequired, String rawEvidence) {
        ClassificationPolicy.Result result = ClassificationPolicy.classify(type, affectsRequired);
        return new ApiChange(changeId(type, method, path, detail, oldValue, newValue),
                type, result.classification(), method, path, null, detail,
                oldValue, newValue, result.reason(), rawEvidence, null);
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
