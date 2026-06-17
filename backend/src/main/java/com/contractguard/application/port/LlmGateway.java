package com.contractguard.application.port;

/**
 * Single seam to the language model (§16). Implementations: an
 * OpenAI-compatible HTTP adapter and a deterministic scripted adapter used
 * for mock mode and all automated tests. Callers validate the returned text
 * against their schema; the gateway itself is schema-agnostic.
 */
public interface LlmGateway {

    LlmResponse complete(LlmRequest request);

    /**
     * @param promptName    versioned prompt identifier, e.g. {@code migration-planner}
     * @param promptVersion prompt resource version, e.g. {@code v1}
     * @param systemPrompt  full system prompt text (already redacted)
     * @param userPayload   bounded, redacted JSON payload for this call
     */
    record LlmRequest(String promptName, String promptVersion, String systemPrompt, String userPayload) {
    }

    /** @param promptTokens / completionTokens -1 when the provider reports no usage */
    record LlmResponse(String content, int promptTokens, int completionTokens) {
    }
}
