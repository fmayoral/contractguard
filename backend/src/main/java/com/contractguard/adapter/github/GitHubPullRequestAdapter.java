package com.contractguard.adapter.github;

import com.contractguard.application.port.PullRequestPort;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.RunFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Minimal GitHub REST client for opening draft pull requests, over
 * {@code java.net.http} — deliberately no vendor SDK, following the LLM
 * gateway's precedent (ADR-0003).
 */
public class GitHubPullRequestAdapter implements PullRequestPort {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String apiBaseUrl;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public GitHubPullRequestAdapter() {
        this("https://api.github.com");
    }

    public GitHubPullRequestAdapter(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @Override
    public PullRequestResult openDraftPullRequest(PullRequestRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("title", request.title());
        body.put("head", request.headBranch());
        body.put("base", request.baseBranch());
        body.put("body", request.body());
        body.put("draft", true);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("%s/repos/%s/%s/pulls".formatted(apiBaseUrl, request.owner(), request.repo())))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + request.credential())
                .header("X-GitHub-Api-Version", "2022-11-28")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response =
                    client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw ContractGuardException.of(FailureCategory.PULL_REQUEST_FAILED,
                        "GitHub returned HTTP %d opening a pull request for %s/%s"
                                .formatted(response.statusCode(), request.owner(), request.repo()),
                        "Check the credential's repo scope and that the head branch was pushed.");
            }
            return parse(response.body());
        } catch (IOException e) {
            throw new ContractGuardException(new RunFailure(FailureCategory.PULL_REQUEST_FAILED,
                    "GitHub API unreachable while opening a pull request", false, null,
                    "Check network access to api.github.com."), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContractGuardException(new RunFailure(FailureCategory.PULL_REQUEST_FAILED,
                    "GitHub API call interrupted", false, null, "Retry the publish."), e);
        }
    }

    private PullRequestResult parse(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            return new PullRequestResult(root.path("html_url").asText(), root.path("number").asInt());
        } catch (IOException e) {
            throw ContractGuardException.of(FailureCategory.PULL_REQUEST_FAILED,
                    "GitHub pull request response could not be parsed",
                    "Verify the GitHub API response shape has not changed.");
        }
    }
}
