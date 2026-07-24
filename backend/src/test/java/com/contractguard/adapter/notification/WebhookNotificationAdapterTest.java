package com.contractguard.adapter.notification;

import com.contractguard.domain.Fixtures;
import com.contractguard.domain.RunState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookNotificationAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile int status = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    /** notify() sends asynchronously by design (ADR-0016); poll briefly for the request to land
     * rather than assuming it already has. */
    private void waitForRequest() {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        while (requestCount.get() == 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    void sendsTheRunAndStateAsJsonWithASlackCompatibleTextField() throws IOException {
        WebhookNotificationAdapter adapter = new WebhookNotificationAdapter(url(), null);

        adapter.notify(Fixtures.newRun(), RunState.AWAITING_APPROVAL);
        waitForRequest();

        assertThat(requestCount.get()).isEqualTo(1);
        JsonNode body = MAPPER.readTree(lastRequestBody.get());
        assertThat(body.path("text").asText()).contains("demo").contains("customer-consumer")
                .contains("awaiting your approval");
        assertThat(body.path("runId").asText()).isEqualTo("run-1");
        assertThat(body.path("runName").asText()).isEqualTo("demo");
        assertThat(body.path("repositoryId").asText()).isEqualTo("customer-consumer");
        assertThat(body.path("state").asText()).isEqualTo("AWAITING_APPROVAL");
        assertThat(body.has("url")).isFalse();
    }

    @Test
    void includesADashboardLinkOnlyWhenABaseUrlIsConfigured() throws IOException {
        WebhookNotificationAdapter adapter = new WebhookNotificationAdapter(url(), "http://192.168.1.20:5173/");

        adapter.notify(Fixtures.newRun(), RunState.SUCCEEDED);
        waitForRequest();

        JsonNode body = MAPPER.readTree(lastRequestBody.get());
        assertThat(body.path("url").asText()).isEqualTo("http://192.168.1.20:5173/runs/run-1");
        assertThat(body.path("text").asText()).contains("succeeded");
    }

    @Test
    void messageWordingMatchesEachNotifiableState() throws IOException {
        WebhookNotificationAdapter adapter = new WebhookNotificationAdapter(url(), null);

        adapter.notify(Fixtures.newRun(), RunState.FAILED);
        waitForRequest();
        assertThat(MAPPER.readTree(lastRequestBody.get()).path("text").asText()).contains("failed")
                .doesNotContain("publish failed");

        requestCount.set(0);
        adapter.notify(Fixtures.newRun(), RunState.PUBLISH_FAILED);
        waitForRequest();
        assertThat(MAPPER.readTree(lastRequestBody.get()).path("text").asText()).contains("publish failed");
    }

    @Test
    void aRejectedResponseIsLoggedAndSwallowedRatherThanThrown() {
        status = 500;
        WebhookNotificationAdapter adapter = new WebhookNotificationAdapter(url(), null);

        adapter.notify(Fixtures.newRun(), RunState.AWAITING_APPROVAL);
        waitForRequest();

        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void anUnreachableEndpointNeverThrows() throws IOException {
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        WebhookNotificationAdapter adapter =
                new WebhookNotificationAdapter("http://127.0.0.1:" + deadPort + "/hook", null);

        adapter.notify(Fixtures.newRun(), RunState.AWAITING_APPROVAL);
        // No assertion beyond "this line was reached and the JVM is still standing" --
        // the point is that a connection refused never surfaces past notify().
    }

    @Test
    void aMalformedUrlNeverThrows() {
        WebhookNotificationAdapter adapter = new WebhookNotificationAdapter("not a valid url", null);

        adapter.notify(Fixtures.newRun(), RunState.AWAITING_APPROVAL);
    }
}
