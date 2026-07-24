package com.contractguard.adapter.notification;

import com.contractguard.application.port.NotificationPort;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.RunState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * POSTs one small JSON payload to a configured webhook URL whenever a run reaches a state
 * needing human attention (FR-031). The top-level {@code text} field alone is enough to render
 * in a Slack incoming webhook; other fields ({@code runId}, {@code state}, ...) are there for
 * receivers that want to do more than display text.
 *
 * <p>Sent asynchronously via {@link HttpClient#sendAsync} so a slow or unreachable endpoint can
 * never add latency to the pipeline step that triggered it, and every failure — build, network,
 * or a non-2xx response — is logged and swallowed rather than propagated, per the
 * {@link NotificationPort} contract: this is a best-effort side channel, never a source of
 * truth (the audit trail and dashboard remain authoritative).
 */
public class WebhookNotificationAdapter implements NotificationPort {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotificationAdapter.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String MESSAGE_PREFIX = "ContractGuard: ";

    private final String webhookUrl;
    private final String dashboardBaseUrl;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebhookNotificationAdapter(String webhookUrl, String dashboardBaseUrl) {
        this.webhookUrl = webhookUrl;
        this.dashboardBaseUrl = dashboardBaseUrl;
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public void notify(AnalysisRun run, RunState state) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload(run, state), StandardCharsets.UTF_8))
                    .build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            log.warn("Webhook notification for run {} failed: {}", run.id(), error.getMessage());
                        } else if (response.statusCode() / 100 != 2) {
                            log.warn("Webhook notification for run {} rejected with HTTP {}",
                                    run.id(), response.statusCode());
                        }
                    });
        } catch (RuntimeException e) {
            log.warn("Webhook notification for run {} could not be sent: {}", run.id(), e.getMessage());
        }
    }

    private String payload(AnalysisRun run, RunState state) {
        ObjectNode body = mapper.createObjectNode();
        body.put("text", message(run, state));
        body.put("runId", run.id());
        body.put("runName", run.name());
        body.put("repositoryId", run.repositoryId());
        body.put("state", state.name());
        if (dashboardBaseUrl != null && !dashboardBaseUrl.isBlank()) {
            body.put("url", dashboardBaseUrl.replaceAll("/+$", "") + "/runs/" + run.id());
        }
        return body.toString();
    }

    private String message(AnalysisRun run, RunState state) {
        String subject = "%s (%s)".formatted(run.name(), run.repositoryId());
        return switch (state) {
            case AWAITING_APPROVAL -> MESSAGE_PREFIX + subject + " is awaiting your approval";
            case SUCCEEDED -> MESSAGE_PREFIX + subject + " succeeded";
            case FAILED -> MESSAGE_PREFIX + subject + " failed";
            case PUBLISH_FAILED -> MESSAGE_PREFIX + subject + " publish failed";
            default -> MESSAGE_PREFIX + subject + " is now " + state;
        };
    }
}
