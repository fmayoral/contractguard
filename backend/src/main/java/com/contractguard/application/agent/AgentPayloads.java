package com.contractguard.application.agent;

import com.contractguard.domain.ApiChange;
import com.contractguard.domain.ImpactAssessment;
import com.contractguard.domain.ImpactEvidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the bounded JSON-friendly payload structures shared by the agent
 * prompts. Only the fields the prompts document are included — never whole
 * domain objects, never repository-wide content (§17.14/15).
 */
final class AgentPayloads {

    private AgentPayloads() {
    }

    static List<Map<String, Object>> changes(List<ApiChange> changes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ApiChange change : changes) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", change.id());
            map.put("type", change.type().name());
            map.put("classification", change.classification().name());
            map.put("method", change.method());
            map.put("path", change.path());
            map.put("schema", change.schema());
            map.put("property", change.property());
            map.put("oldValue", change.oldValue());
            map.put("newValue", change.newValue());
            map.put("reason", change.reason());
            result.add(map);
        }
        return result;
    }

    static List<Map<String, Object>> evidence(List<ImpactEvidence> evidence) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ImpactEvidence item : evidence) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", item.id());
            map.put("apiChangeId", item.apiChangeId());
            map.put("relativePath", item.relativePath());
            map.put("startLine", item.startLine());
            map.put("endLine", item.endLine());
            map.put("snippet", item.snippet());
            map.put("searchTerm", item.searchTerm());
            map.put("relationship", item.relationship());
            result.add(map);
        }
        return result;
    }

    static List<Map<String, Object>> assessments(List<ImpactAssessment> assessments) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ImpactAssessment assessment : assessments) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("apiChangeId", assessment.apiChangeId());
            map.put("component", assessment.component());
            map.put("severity", assessment.severity().name());
            map.put("confidence", assessment.confidence().name());
            map.put("failureMode", assessment.failureMode());
            map.put("recommendedAction", assessment.recommendedAction());
            map.put("assumptions", assessment.assumptions());
            map.put("evidenceIds", assessment.evidenceIds());
            result.add(map);
        }
        return result;
    }
}
