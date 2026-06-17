package com.contractguard.application.port;

/**
 * JSON (de)serialisation port so the application layer can validate
 * structured LLM output and build payloads without depending on a JSON
 * library. Implementations must reject unknown fields: extra properties in
 * model output are a schema violation, not something to ignore.
 */
public interface JsonCodec {

    <T> DecodeResult<T> decode(String json, Class<T> type);

    String encode(Object value);

    /** Exactly one of {@code value} / {@code error} is set. */
    record DecodeResult<T>(T value, String error) {

        public boolean ok() {
            return error == null;
        }

        public static <T> DecodeResult<T> success(T value) {
            return new DecodeResult<>(value, null);
        }

        public static <T> DecodeResult<T> failure(String error) {
            return new DecodeResult<>(null, error);
        }
    }
}
