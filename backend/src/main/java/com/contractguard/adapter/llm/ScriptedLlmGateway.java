package com.contractguard.adapter.llm;

import com.contractguard.application.port.LlmGateway;
import com.contractguard.domain.DeterministicRemediation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic gateway for mock mode and automated tests (§16, ADR-0003).
 * Responses are computed from the request payload alone — same input, same
 * output — and honour the same JSON contracts as a real model, so the entire
 * workflow including schema validation runs identically in both modes.
 */
public class ScriptedLlmGateway implements LlmGateway {

    // JSON field names shared across the request/response payloads this mock gateway builds.
    private static final String FIELD_CHANGES = "changes";
    private static final String FIELD_CLASSIFICATION = "classification";
    private static final String FIELD_OLD_VALUE = "oldValue";
    private static final String FIELD_NEW_VALUE = "newValue";
    private static final String FIELD_RELATIVE_PATH = "relativePath";
    private static final String FIELD_API_CHANGE_ID = "apiChangeId";
    // ApiChange.type() values this mock gateway gives mechanical, scripted treatment.
    private static final String TYPE_ENDPOINT_RENAMED = "ENDPOINT_RENAMED";
    private static final String TYPE_PROPERTY_RENAMED = "PROPERTY_RENAMED";
    private static final String TYPE_ENUM_VALUE_REMOVED = "ENUM_VALUE_REMOVED";
    private static final String TYPE_PROPERTY_ADDED = "PROPERTY_ADDED";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public LlmResponse complete(LlmRequest request) {
        JsonNode payload = readPayload(request.userPayload());
        String content = switch (request.promptName()) {
            case "change-explainer" -> explain(payload);
            case "impact-assessor" -> investigate(payload);
            case "migration-planner" -> plan(payload);
            case "implementation-agent", "repair-agent" -> rewrite(payload);
            default -> throw new IllegalArgumentException(
                    "scripted gateway has no behaviour for prompt '%s'".formatted(request.promptName()));
        };
        return new LlmResponse(content, -1, -1);
    }

    private JsonNode readPayload(String userPayload) {
        try {
            // Validation feedback appended after a retry is not part of the JSON document.
            int end = userPayload.lastIndexOf('}');
            return mapper.readTree(userPayload.substring(0, end + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("scripted gateway received a non-JSON payload", e);
        }
    }

    private String explain(JsonNode payload) {
        ObjectNode response = mapper.createObjectNode();
        ArrayNode explanations = response.putArray("explanations");
        for (JsonNode change : payload.path(FIELD_CHANGES)) {
            ObjectNode item = explanations.addObject();
            item.put("changeId", change.path("id").asText());
            item.put("explanation", explanationFor(change));
            item.put("uncertainty", change.path(FIELD_CLASSIFICATION).asText().equals("UNKNOWN")
                    ? "The change category is outside the analysed set; manual review advised." : "");
        }
        return response.toString();
    }

    private String explanationFor(JsonNode change) {
        String type = change.path("type").asText();
        String oldValue = change.path(FIELD_OLD_VALUE).asText();
        String newValue = change.path(FIELD_NEW_VALUE).asText();
        return switch (type) {
            case TYPE_ENDPOINT_RENAMED -> ("Callers of %s now receive 404 responses because the endpoint moved to %s. "
                    + "Every client URL, constant and documentation reference must be updated.")
                    .formatted(oldValue, newValue);
            case TYPE_PROPERTY_RENAMED -> ("Responses no longer contain '%s'; the value is serialised as '%s'. "
                    + "Consumers deserialising by field name will read null and may fail downstream.")
                    .formatted(oldValue, newValue);
            case TYPE_ENUM_VALUE_REMOVED -> ("The value '%s' can no longer appear in responses. Handling code becomes "
                    + "unreachable, and consumer enums declaring it drift from the contract.")
                    .formatted(oldValue);
            case TYPE_PROPERTY_ADDED -> ("The optional field '%s' was added. Existing consumers ignore unknown fields "
                    + "by default, so no action is required.").formatted(newValue);
            default -> "Change of type %s from '%s' to '%s'.".formatted(type, oldValue, newValue);
        };
    }

    /** One demonstrative bounded tool call, then evidence-cited assessments. */
    private String investigate(JsonNode payload) {
        JsonNode evidence = payload.path("evidence");
        boolean hasToolResults = payload.path("toolResults").size() > 0;
        int stepsRemaining = payload.path("stepsRemaining").asInt(0);
        if (!hasToolResults && stepsRemaining > 1 && evidence.size() > 0) {
            JsonNode first = evidence.get(0);
            ObjectNode response = mapper.createObjectNode();
            response.put("action", "read_source_file");
            ObjectNode read = response.putObject("read");
            read.put("path", first.path(FIELD_RELATIVE_PATH).asText());
            read.put("startLine", Math.max(1, first.path("startLine").asInt(1) - 2));
            read.put("endLine", first.path("endLine").asInt(1) + 2);
            response.putNull("search");
            response.putNull("assessments");
            return response.toString();
        }
        Map<String, List<JsonNode>> evidenceByChange = new LinkedHashMap<>();
        for (JsonNode item : evidence) {
            evidenceByChange.computeIfAbsent(item.path(FIELD_API_CHANGE_ID).asText(), k -> new ArrayList<>()).add(item);
        }
        ObjectNode response = mapper.createObjectNode();
        response.put("action", "finish");
        response.putNull("search");
        response.putNull("read");
        ArrayNode assessments = response.putArray("assessments");
        for (JsonNode change : payload.path(FIELD_CHANGES)) {
            List<JsonNode> items = evidenceByChange.get(change.path("id").asText());
            if (items == null || items.isEmpty()) {
                continue;
            }
            ObjectNode assessment = assessments.addObject();
            assessment.put(FIELD_API_CHANGE_ID, change.path("id").asText());
            assessment.put("component", items.get(0).path(FIELD_RELATIVE_PATH).asText());
            assessment.put("severity", severityFor(change.path(FIELD_CLASSIFICATION).asText()));
            assessment.put("confidence", "HIGH");
            assessment.put("failureMode", failureModeFor(change));
            assessment.put("recommendedAction", recommendedActionFor(change));
            ArrayNode assumptions = assessment.putArray("assumptions");
            assumptions.add("Evidence line matches reflect real usage, not dead code.");
            ArrayNode ids = assessment.putArray("evidenceIds");
            items.forEach(item -> ids.add(item.path("id").asText()));
        }
        return response.toString();
    }

    private String severityFor(String classification) {
        return switch (classification) {
            case "BREAKING" -> "HIGH";
            case "POTENTIALLY_BREAKING" -> "MEDIUM";
            default -> "LOW";
        };
    }

    private String failureModeFor(JsonNode change) {
        return switch (change.path("type").asText()) {
            case TYPE_ENDPOINT_RENAMED -> "HTTP 404 on every call to the old path at runtime";
            case TYPE_PROPERTY_RENAMED -> "Deserialised field is null; assertions and business logic misbehave";
            case TYPE_ENUM_VALUE_REMOVED -> "Dead handling branch and contract drift in the consumer enum";
            case TYPE_PROPERTY_ADDED -> "None expected; unknown fields are ignored";
            default -> "Unclassified failure mode";
        };
    }

    private String recommendedActionFor(JsonNode change) {
        return switch (change.path("type").asText()) {
            case TYPE_ENDPOINT_RENAMED -> "Update the client path constant, built URLs and endpoint documentation to "
                    + change.path(FIELD_NEW_VALUE).asText();
            case TYPE_PROPERTY_RENAMED -> "Rename the DTO field and accessors to "
                    + change.path(FIELD_NEW_VALUE).asText() + " and update tests";
            case TYPE_ENUM_VALUE_REMOVED -> "Remove the " + change.path(FIELD_OLD_VALUE).asText()
                    + " constant and its handling branches and tests";
            case TYPE_PROPERTY_ADDED -> "Optionally map the new field; no change required";
            default -> "Review the change manually";
        };
    }

    private String plan(JsonNode payload) {
        String validationCommand = payload.path("validationCommands").path(0).asText("maven-verify");
        Map<String, List<JsonNode>> evidenceByChange = new LinkedHashMap<>();
        for (JsonNode item : payload.path("evidence")) {
            evidenceByChange.computeIfAbsent(item.path(FIELD_API_CHANGE_ID).asText(), k -> new ArrayList<>()).add(item);
        }
        ObjectNode response = mapper.createObjectNode();
        ArrayNode items = response.putArray("items");
        for (JsonNode change : payload.path(FIELD_CHANGES)) {
            planItemFor(change, evidenceByChange, validationCommand).ifPresent(items::add);
        }
        return response.toString();
    }

    /** @return the plan item for this change, or empty if it's non-breaking or has no usable evidence */
    private Optional<ObjectNode> planItemFor(JsonNode change, Map<String, List<JsonNode>> evidenceByChange,
            String validationCommand) {
        if (!"BREAKING".equals(change.path(FIELD_CLASSIFICATION).asText())) {
            return Optional.empty();
        }
        List<JsonNode> evidence = evidenceByChange.get(change.path("id").asText());
        if (evidence == null || evidence.isEmpty()) {
            return Optional.empty();
        }
        Set<String> sourceFiles = new LinkedHashSet<>();
        Set<String> testFiles = new LinkedHashSet<>();
        Set<String> evidenceIds = new LinkedHashSet<>();
        for (JsonNode item : evidence) {
            String path = item.path(FIELD_RELATIVE_PATH).asText();
            if (path.contains("src/test/")) {
                testFiles.add(path);
            } else {
                sourceFiles.add(path);
            }
            evidenceIds.add(item.path("id").asText());
        }
        if (sourceFiles.isEmpty() && testFiles.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode item = mapper.createObjectNode();
        item.put("objective", objectiveFor(change));
        ArrayNode expected = item.putArray("expectedFiles");
        (sourceFiles.isEmpty() ? testFiles : sourceFiles).forEach(expected::add);
        item.put("proposedAction", recommendedActionFor(change));
        ArrayNode tests = item.putArray("testsToUpdate");
        if (!sourceFiles.isEmpty()) {
            testFiles.forEach(tests::add);
        }
        item.put("validationCommand", validationCommand);
        item.put("risk", "low: mechanical rename/removal with test coverage");
        item.put("rollback", "Discard the working branch; the original branch is untouched");
        ArrayNode ids = item.putArray("evidenceIds");
        evidenceIds.forEach(ids::add);
        return Optional.of(item);
    }

    private String objectiveFor(JsonNode change) {
        return switch (change.path("type").asText()) {
            case TYPE_ENDPOINT_RENAMED -> "Migrate client calls from %s to %s"
                    .formatted(change.path(FIELD_OLD_VALUE).asText(), change.path(FIELD_NEW_VALUE).asText());
            case TYPE_PROPERTY_RENAMED -> "Rename consumer field %s to %s"
                    .formatted(change.path(FIELD_OLD_VALUE).asText(), change.path(FIELD_NEW_VALUE).asText());
            case TYPE_ENUM_VALUE_REMOVED -> "Remove handling of retired enum value %s"
                    .formatted(change.path(FIELD_OLD_VALUE).asText());
            default -> "Address change " + change.path("id").asText();
        };
    }

    private String rewrite(JsonNode payload) {
        List<DeterministicRemediation.ChangeSpec> specs = new ArrayList<>();
        for (JsonNode change : payload.path(FIELD_CHANGES)) {
            specs.add(new DeterministicRemediation.ChangeSpec(
                    change.path("type").asText(),
                    change.path(FIELD_OLD_VALUE).asText(),
                    change.path(FIELD_NEW_VALUE).asText()));
        }
        ObjectNode response = mapper.createObjectNode();
        ArrayNode files = response.putArray("files");
        for (JsonNode file : payload.path("files")) {
            String path = file.path("path").asText();
            String content = file.path("content").asText();
            String transformed = DeterministicRemediation.transform(content, specs);
            if (!transformed.equals(content)) {
                ObjectNode rewritten = files.addObject();
                rewritten.put("path", path);
                rewritten.put("newContent", transformed);
            }
        }
        response.put("notes", "Deterministic mock-mode remediation derived from the approved plan.");
        return response.toString();
    }
}
