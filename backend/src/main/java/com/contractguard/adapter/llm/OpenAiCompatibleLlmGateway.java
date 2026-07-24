package com.contractguard.adapter.llm;

import com.contractguard.application.port.LlmGateway;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.RunFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Minimal OpenAI-compatible {@code /chat/completions} client over
 * {@code java.net.http} — deliberately no vendor SDK (ADR-0003).
 */
public class OpenAiCompatibleLlmGateway implements LlmGateway {

    private static final String FIELD_CONTENT = "content";

    private final LlmSettings settings;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleLlmGateway(LlmSettings settings) {
        this.settings = settings;
        this.client = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", settings.model());
        body.put("temperature", settings.temperature());
        body.put("max_tokens", settings.maxTokens());
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put(FIELD_CONTENT, request.systemPrompt());
        messages.addObject().put("role", "user").put(FIELD_CONTENT, request.userPayload());

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(settings.baseUrl() + "/chat/completions"))
                .timeout(settings.timeout())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + settings.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response =
                    client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw ContractGuardException.of(FailureCategory.LLM_UNAVAILABLE,
                        "LLM provider returned HTTP %d for prompt '%s'"
                                .formatted(response.statusCode(), request.promptName()),
                        "Check the configured base URL, API key and model.");
            }
            return parse(response.body(), request.promptName());
        } catch (IOException e) {
            throw new ContractGuardException(new RunFailure(FailureCategory.LLM_UNAVAILABLE,
                    "LLM provider unreachable for prompt '%s'".formatted(request.promptName()),
                    false, null, "Check network access and the configured base URL."), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContractGuardException(new RunFailure(FailureCategory.LLM_UNAVAILABLE,
                    "LLM call interrupted", false, null, "Retry the run."), e);
        }
    }

    private LlmResponse parse(String body, String promptName) throws IOException {
        JsonNode root = mapper.readTree(body);
        JsonNode content = root.path("choices").path(0).path("message").path(FIELD_CONTENT);
        if (content.isMissingNode() || content.isNull()) {
            throw ContractGuardException.of(FailureCategory.INVALID_LLM_RESPONSE,
                    "LLM provider response for prompt '%s' has no message content".formatted(promptName),
                    "Verify the endpoint implements the OpenAI chat completions contract.");
        }
        JsonNode usage = root.path("usage");
        return new LlmResponse(content.asText(),
                usage.path("prompt_tokens").asInt(-1),
                usage.path("completion_tokens").asInt(-1));
    }
}
