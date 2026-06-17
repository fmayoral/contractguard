package com.contractguard.application.llm;

import com.contractguard.application.policy.SecretRedactor;
import com.contractguard.application.port.JsonCodec;
import com.contractguard.application.port.LlmGateway;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;

import java.util.List;
import java.util.function.Function;

/**
 * Schema-validated structured-output channel to the model (§16): payloads are
 * redacted before submission, responses are strictly decoded and semantically
 * validated, one retry carries the validation feedback back to the model, and
 * a second failure surfaces as a typed {@code INVALID_LLM_RESPONSE}.
 */
public class LlmJsonClient {

    private final LlmGateway gateway;
    private final JsonCodec codec;

    public LlmJsonClient(LlmGateway gateway, JsonCodec codec) {
        this.gateway = gateway;
        this.codec = codec;
    }

    /**
     * @param semanticValidator returns violation messages, empty when the
     *                          decoded value is acceptable
     */
    public <T> T request(PromptLibrary.Prompt prompt, String payloadJson, Class<T> type,
            Function<T, List<String>> semanticValidator) {
        String redactedPayload = SecretRedactor.redact(payloadJson);
        String feedback = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String payload = feedback == null ? redactedPayload
                    : redactedPayload + "\n\nYour previous response was invalid: " + feedback
                            + "\nRespond again with corrected JSON only.";
            LlmGateway.LlmResponse response = gateway.complete(new LlmGateway.LlmRequest(
                    prompt.name(), prompt.version(), prompt.text(), payload));
            String content = stripCodeFences(response.content());
            JsonCodec.DecodeResult<T> decoded = codec.decode(content, type);
            if (!decoded.ok()) {
                feedback = "malformed JSON for the expected schema: " + decoded.error();
                continue;
            }
            List<String> violations = semanticValidator.apply(decoded.value());
            if (violations.isEmpty()) {
                return decoded.value();
            }
            feedback = String.join("; ", violations);
        }
        throw ContractGuardException.of(FailureCategory.INVALID_LLM_RESPONSE,
                "model output for prompt '%s' failed validation after retry: %s"
                        .formatted(prompt.name(), feedback),
                "Re-run the step; if the failure persists, inspect the prompt/model configuration.");
    }

    /** Models often wrap JSON in Markdown fences despite instructions; tolerate that one quirk. */
    static String stripCodeFences(String content) {
        String trimmed = content.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }
}
