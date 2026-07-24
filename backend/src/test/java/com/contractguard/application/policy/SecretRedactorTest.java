package com.contractguard.application.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    @Test
    void redactsKeyValueCredentials() {
        String input = """
                spring.datasource.password=hunter2
                api_key: sk-live-abcdefghijklmnop
                client_secret = xyz123
                """;
        String redacted = SecretRedactor.redact(input);
        assertThat(redacted).doesNotContain("hunter2").doesNotContain("xyz123")
                .contains("password=[REDACTED]");
    }

    @Test
    void redactsAuthorizationHeadersAndTokenShapes() {
        String input = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.abcdef12345\n"
                + "key sk-proj-1234567890abcdefghij and ghp_abcdefghij1234567890 and AKIAIOSFODNN7EXAMPLE";
        String redacted = SecretRedactor.redact(input);
        assertThat(redacted)
                .doesNotContain("ghp_abcdefghij1234567890")
                .doesNotContain("AKIAIOSFODNN7EXAMPLE")
                .doesNotContain("sk-proj-1234567890abcdefghij");
    }

    @Test
    void redactsPemBlocks() {
        String input = "-----BEGIN RSA PRIVATE KEY-----\nMIIEow...\n-----END RSA PRIVATE KEY-----";
        assertThat(SecretRedactor.redact(input)).isEqualTo("[REDACTED]");
    }

    @Test
    void leavesOrdinaryCodeAlone() {
        String input = "String fullName = customer.getFullName(); // path /customers/{id}";
        assertThat(SecretRedactor.redact(input)).isEqualTo(input);
        assertThat(SecretRedactor.redact("")).isEmpty();
        assertThat(SecretRedactor.redact(null)).isNull();
    }
}
