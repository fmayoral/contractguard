package com.contractguard.application.agent;

import com.contractguard.adapter.json.JacksonJsonCodec;
import com.contractguard.application.llm.LlmJsonClient;
import com.contractguard.application.llm.PromptLibrary;
import com.contractguard.domain.ApiChange;
import com.contractguard.domain.ChangeType;
import com.contractguard.domain.Classification;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.testsupport.NoOpObservability;
import com.contractguard.testsupport.QueuedLlmGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeExplainerTest {

    private final JacksonJsonCodec codec = new JacksonJsonCodec();
    private final PromptLibrary prompts = new PromptLibrary();

    private static ApiChange change(String id) {
        return new ApiChange(id, ChangeType.PROPERTY_RENAMED, Classification.BREAKING,
                null, null, "Customer", "fullName", "fullName", "displayName",
                "REASON", "{}", null);
    }

    @Test
    void mapsExplanationsByChangeId() {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue("""
                {"explanations":[{"changeId":"c1","explanation":"breaks readers","uncertainty":""}]}""");
        ChangeExplainer explainer = new ChangeExplainer(
                new LlmJsonClient(gateway, codec, new NoOpObservability()), prompts, codec);

        Map<String, String> result = explainer.explain(List.of(change("c1")));

        assertThat(result).containsEntry("c1", "breaks readers");
        assertThat(gateway.requests().get(0).userPayload()).contains("fullName");
    }

    @Test
    void uncertaintyIsAppendedWhenPresent() {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue("""
                {"explanations":[{"changeId":"c1","explanation":"breaks readers",
                  "uncertainty":"unsure about dead code"}]}""");
        ChangeExplainer explainer = new ChangeExplainer(
                new LlmJsonClient(gateway, codec, new NoOpObservability()), prompts, codec);

        Map<String, String> result = explainer.explain(List.of(change("c1")));

        assertThat(result.get("c1")).contains("Uncertainty: unsure about dead code");
    }

    @Test
    void missingOrUnknownChangeIdsTriggerRetryThenFail() {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue(
                "{\"explanations\":[{\"changeId\":\"ghost\",\"explanation\":\"x\",\"uncertainty\":\"\"}]}",
                "{\"explanations\":[]}");
        ChangeExplainer explainer = new ChangeExplainer(
                new LlmJsonClient(gateway, codec, new NoOpObservability()), prompts, codec);

        assertThatThrownBy(() -> explainer.explain(List.of(change("c1"))))
                .isInstanceOf(ContractGuardException.class);
        assertThat(gateway.requests()).hasSize(2);
        assertThat(gateway.requests().get(1).userPayload()).contains("unknown changeId");
    }
}
