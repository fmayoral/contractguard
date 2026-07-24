package com.contractguard.adapter.llm;

import com.contractguard.application.port.LlmGateway.LlmRequest;
import com.contractguard.application.port.LlmGateway.LlmResponse;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleLlmGatewayTest {

    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenAiCompatibleLlmGateway gateway() {
        return new OpenAiCompatibleLlmGateway(new LlmSettings(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-key", "test-model", Duration.ofSeconds(5), 512, 0.0));
    }

    @Test
    void sendsChatCompletionRequestAndParsesContentAndUsage() {
        responseBody = """
                {"choices":[{"message":{"role":"assistant","content":"{\\"ok\\":true}"}}],
                 "usage":{"prompt_tokens":42,"completion_tokens":7}}""";

        LlmResponse response = gateway().complete(
                new LlmRequest("migration-planner", "v1", "system prompt", "{\"data\":1}"));

        assertThat(response.content()).isEqualTo("{\"ok\":true}");
        assertThat(response.promptTokens()).isEqualTo(42);
        assertThat(response.completionTokens()).isEqualTo(7);
        assertThat(lastRequestBody.get())
                .contains("\"model\":\"test-model\"")
                .contains("system prompt")
                .contains("{\\\"data\\\":1}");
    }

    @Test
    void nonSuccessStatusFailsAsLlmUnavailable() {
        status = 500;
        OpenAiCompatibleLlmGateway gateway = gateway();
        LlmRequest request = new LlmRequest("p", "v1", "s", "{}");
        assertThatThrownBy(() -> gateway.complete(request))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.LLM_UNAVAILABLE));
    }

    @Test
    void missingContentFailsAsInvalidResponse() {
        responseBody = "{\"choices\":[]}";
        OpenAiCompatibleLlmGateway gateway = gateway();
        LlmRequest request = new LlmRequest("p", "v1", "s", "{}");
        assertThatThrownBy(() -> gateway.complete(request))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.INVALID_LLM_RESPONSE));
    }

    @Test
    void unreachableHostFailsAsLlmUnavailable() {
        OpenAiCompatibleLlmGateway unreachable = new OpenAiCompatibleLlmGateway(new LlmSettings(
                "http://127.0.0.1:1/v1", "k", "m", Duration.ofMillis(500), 16, 0.0));
        LlmRequest request = new LlmRequest("p", "v1", "s", "{}");
        assertThatThrownBy(() -> unreachable.complete(request))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.LLM_UNAVAILABLE));
    }
}
