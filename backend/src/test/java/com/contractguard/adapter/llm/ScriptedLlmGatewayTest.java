package com.contractguard.adapter.llm;

import com.contractguard.application.port.LlmGateway.LlmRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptedLlmGatewayTest {

    private final ScriptedLlmGateway gateway = new ScriptedLlmGateway();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode call(String promptName, String payload) throws Exception {
        String content = gateway.complete(
                new LlmRequest(promptName, "v1", "system", payload)).content();
        return mapper.readTree(content);
    }

    @Test
    void explainerProducesOneExplanationPerChange() throws Exception {
        JsonNode response = call("change-explainer", """
                {"changes":[
                  {"id":"c1","type":"ENDPOINT_RENAMED","classification":"BREAKING",
                   "oldValue":"/customers/{id}","newValue":"/v2/customers/{id}"},
                  {"id":"c2","type":"PROPERTY_ADDED","classification":"NON_BREAKING",
                   "oldValue":null,"newValue":"preferredLanguage"}
                ]}""");
        assertThat(response.path("explanations")).hasSize(2);
        assertThat(response.path("explanations").get(0).path("explanation").asText())
                .contains("/v2/customers/{id}");
        assertThat(response.path("explanations").get(1).path("changeId").asText()).isEqualTo("c2");
    }

    @Test
    void investigatorFirstRequestsABoundedRead() throws Exception {
        JsonNode response = call("impact-assessor", """
                {"changes":[{"id":"c1","type":"PROPERTY_RENAMED","classification":"BREAKING"}],
                 "evidence":[{"id":"e1","apiChangeId":"c1","relativePath":"src/A.java",
                              "startLine":10,"endLine":10,"snippet":"x","searchTerm":"t","relationship":"r"}],
                 "toolResults":[],"stepsRemaining":5}""");
        assertThat(response.path("action").asText()).isEqualTo("read_source_file");
        assertThat(response.path("read").path("path").asText()).isEqualTo("src/A.java");
    }

    @Test
    void investigatorFinishesWithEvidenceCitedAssessments() throws Exception {
        JsonNode response = call("impact-assessor", """
                {"changes":[
                   {"id":"c1","type":"PROPERTY_RENAMED","classification":"BREAKING",
                    "oldValue":"fullName","newValue":"displayName"},
                   {"id":"c2","type":"PROPERTY_ADDED","classification":"NON_BREAKING",
                    "oldValue":null,"newValue":"x"}],
                 "evidence":[{"id":"e1","apiChangeId":"c1","relativePath":"src/A.java",
                              "startLine":10,"endLine":10,"snippet":"x","searchTerm":"t","relationship":"r"}],
                 "toolResults":[{"tool":"read_source_file","detail":"src/A.java","content":"..."}],
                 "stepsRemaining":4}""");
        assertThat(response.path("action").asText()).isEqualTo("finish");
        assertThat(response.path("assessments")).hasSize(1);
        JsonNode assessment = response.path("assessments").get(0);
        assertThat(assessment.path("apiChangeId").asText()).isEqualTo("c1");
        assertThat(assessment.path("severity").asText()).isEqualTo("HIGH");
        assertThat(assessment.path("evidenceIds").get(0).asText()).isEqualTo("e1");
    }

    @Test
    void plannerCoversOnlyBreakingChangesWithEvidence() throws Exception {
        JsonNode response = call("migration-planner", """
                {"changes":[
                   {"id":"c1","type":"PROPERTY_RENAMED","classification":"BREAKING",
                    "oldValue":"fullName","newValue":"displayName"},
                   {"id":"c2","type":"PROPERTY_ADDED","classification":"NON_BREAKING",
                    "oldValue":null,"newValue":"x"},
                   {"id":"c3","type":"ENDPOINT_REMOVED","classification":"BREAKING",
                    "oldValue":"/gone","newValue":null}],
                 "assessments":[],
                 "evidence":[
                   {"id":"e1","apiChangeId":"c1","relativePath":"src/main/java/A.java"},
                   {"id":"e2","apiChangeId":"c1","relativePath":"src/test/java/ATest.java"}],
                 "validationCommands":["maven-verify"]}""");
        assertThat(response.path("items")).hasSize(1);
        JsonNode item = response.path("items").get(0);
        assertThat(item.path("expectedFiles").get(0).asText()).isEqualTo("src/main/java/A.java");
        assertThat(item.path("testsToUpdate").get(0).asText()).isEqualTo("src/test/java/ATest.java");
        assertThat(item.path("validationCommand").asText()).isEqualTo("maven-verify");
        assertThat(item.path("evidenceIds")).hasSize(2);
    }

    @Test
    void implementationRewritesOnlyChangedFiles() throws Exception {
        JsonNode response = call("implementation-agent", """
                {"changes":[{"id":"c1","type":"PROPERTY_RENAMED","classification":"BREAKING",
                             "oldValue":"fullName","newValue":"displayName"}],
                 "planItems":[],
                 "files":[
                   {"path":"src/A.java","content":"String fullName;"},
                   {"path":"src/B.java","content":"int unrelated;"}]}""");
        assertThat(response.path("files")).hasSize(1);
        assertThat(response.path("files").get(0).path("path").asText()).isEqualTo("src/A.java");
        assertThat(response.path("files").get(0).path("newContent").asText())
                .isEqualTo("String displayName;");
        assertThat(response.path("notes").asText()).isNotBlank();
    }

    @Test
    void unknownPromptIsRejected() {
        LlmRequest request = new LlmRequest("mystery-prompt", "v1", "s", "{}");
        assertThatThrownBy(() -> gateway.complete(request))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
