package com.contractguard.adapter.json;

import com.contractguard.application.port.JsonCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict Jackson codec: unknown fields fail, satisfying schema validation of model output. */
public class JacksonJsonCodec implements JsonCodec {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

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
