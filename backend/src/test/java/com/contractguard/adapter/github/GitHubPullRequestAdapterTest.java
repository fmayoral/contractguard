package com.contractguard.adapter.github;

import com.contractguard.application.port.PullRequestPort.PullRequestRequest;
import com.contractguard.application.port.PullRequestPort.PullRequestResult;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubPullRequestAdapterTest {

    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
    private volatile int status = 201;
    private volatile String responseBody = """
            {"html_url":"https://github.com/acme/widgets/pull/7","number":7}""";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/acme/widgets/pulls", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
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

    private GitHubPullRequestAdapter adapter() {
        return new GitHubPullRequestAdapter("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private PullRequestRequest request() {
        return new PullRequestRequest("acme", "widgets", "contractguard/run-1a2b3c4d", "main",
                "ContractGuard: remediate customer-api v2", "## Detected changes\n\n- ...", "gh-token");
    }

    @Test
    void opensADraftPullRequestAndParsesTheResponse() {
        PullRequestResult result = adapter().openDraftPullRequest(request());

        assertThat(result.url()).isEqualTo("https://github.com/acme/widgets/pull/7");
        assertThat(result.number()).isEqualTo(7);
        assertThat(lastRequestBody.get()).contains("\"draft\":true")
                .contains("\"head\":\"contractguard/run-1a2b3c4d\"")
                .contains("\"base\":\"main\"");
        assertThat(lastAuthHeader.get()).isEqualTo("Bearer gh-token");
    }

    @Test
    void nonSuccessStatusRaisesATypedFailure() {
        status = 422;
        responseBody = """
                {"message":"Validation Failed"}""";

        assertThatThrownBy(() -> adapter().openDraftPullRequest(request()))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.PULL_REQUEST_FAILED));
    }
}
