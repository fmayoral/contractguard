package com.contractguard.application.llm;

import com.contractguard.adapter.json.JacksonJsonCodec;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.testsupport.QueuedLlmGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmJsonClientTest {

    record Answer(String value) {
    }

    private final PromptLibrary.Prompt prompt = new PromptLibrary.Prompt("change-explainer", "v1", "system text");

    @Test
    void returnsDecodedValueOnFirstValidResponse() {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue("{\"value\":\"ok\"}");
        LlmJsonClient client = new LlmJsonClient(gateway, new JacksonJsonCodec());

        Answer answer = client.request(prompt, "{}", Answer.class, a -> List.of());

        assertThat(answer.value()).isEqualTo("ok");
        assertThat(gateway.requests()).hasSize(1);
        assertThat(gateway.requests().get(0).promptName()).isEqualTo("change-explainer");
    }

    @Test
    void retriesOnceWithValidationFeedbackThenSucceeds() {
        QueuedLlmGateway gateway = new QueuedLlmGateway()
                .enqueue("not json at all", "{\"value\":\"fixed\"}");
        LlmJsonClient client = new LlmJsonClient(gateway, new JacksonJsonCodec());

        Answer answer = client.request(prompt, "{}", Answer.class, a -> List.of());

        assertThat(answer.value()).isEqualTo("fixed");
        assertThat(gateway.requests()).hasSize(2);
        assertThat(gateway.requests().get(1).userPayload()).contains("previous response was invalid");
    }

    @Test
    void semanticViolationsTriggerRetryAndFeedback() {
        QueuedLlmGateway gateway = new QueuedLlmGateway()
                .enqueue("{\"value\":\"bad\"}", "{\"value\":\"good\"}");
        LlmJsonClient client = new LlmJsonClient(gateway, new JacksonJsonCodec());

        Answer answer = client.request(prompt, "{}", Answer.class,
                a -> a.value().equals("bad") ? List.of("value must not be 'bad'") : List.of());

        assertThat(answer.value()).isEqualTo("good");
        assertThat(gateway.requests().get(1).userPayload()).contains("value must not be 'bad'");
    }

    @Test
    void failsTypedAfterSecondInvalidResponse() {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue("garbage", "still garbage");
        LlmJsonClient client = new LlmJsonClient(gateway, new JacksonJsonCodec());

        assertThatThrownBy(() -> client.request(prompt, "{}", Answer.class, a -> List.of()))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.INVALID_LLM_RESPONSE));
        assertThat(gateway.requests()).hasSize(2);
    }

    @Test
    void unknownFieldsAreASchemaViolation() {
        QueuedLlmGateway gateway = new QueuedLlmGateway()
                .enqueue("{\"value\":\"ok\",\"extra\":\"nope\"}", "{\"value\":\"ok\"}");
        LlmJsonClient client = new LlmJsonClient(gateway, new JacksonJsonCodec());

        Answer answer = client.request(prompt, "{}", Answer.class, a -> List.of());

        assertThat(answer.value()).isEqualTo("ok");
        assertThat(gateway.requests()).hasSize(2);
    }

    @Test
    void payloadIsRedactedBeforeSubmission() {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue("{\"value\":\"ok\"}");
        LlmJsonClient client = new LlmJsonClient(gateway, new JacksonJsonCodec());

        client.request(prompt, "{\"config\":\"password=topsecret99\"}", Answer.class, a -> List.of());

        assertThat(gateway.requests().get(0).userPayload())
                .doesNotContain("topsecret99")
                .contains("[REDACTED]");
    }

    @Test
    void markdownFencesAreTolerated() {
        QueuedLlmGateway gateway = new QueuedLlmGateway()
                .enqueue("```json\n{\"value\":\"fenced\"}\n```");
        LlmJsonClient client = new LlmJsonClient(gateway, new JacksonJsonCodec());

        Answer answer = client.request(prompt, "{}", Answer.class, a -> List.of());

        assertThat(answer.value()).isEqualTo("fenced");
        assertThat(LlmJsonClient.stripCodeFences("plain")).isEqualTo("plain");
    }
}
