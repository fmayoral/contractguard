package com.contractguard.adapter.llm;

import java.time.Duration;

/** Connection settings for the OpenAI-compatible gateway (§16). */
public record LlmSettings(
        String baseUrl,
        String apiKey,
        String model,
        Duration timeout,
        int maxTokens,
        double temperature) {
}
