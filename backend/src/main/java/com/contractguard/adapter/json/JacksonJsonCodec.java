package com.contractguard.adapter.json;

import com.contractguard.application.port.JsonCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Strict Jackson codec: unknown fields fail, satisfying schema validation of model output.
 * {@code decode} is only ever used to parse raw LLM completions (§16), so it tolerates the one
 * malformed-JSON quirk models commonly produce on long structured output -- a trailing comma
 * before a closing brace/bracket -- rather than spending one of {@link
 * com.contractguard.application.llm.LlmJsonClient}'s two attempts on a syntax slip that isn't a
 * genuine schema violation.
 */
public class JacksonJsonCodec implements JsonCodec {

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .build();

    @Override
    public <T> DecodeResult<T> decode(String json, Class<T> type) {
        try {
            return DecodeResult.success(mapper.readValue(json, type));
        } catch (JsonProcessingException e) {
            return DecodeResult.failure(e.getOriginalMessage());
        }
    }

    @Override
    public String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("value cannot be encoded as JSON", e);
        }
    }
}
