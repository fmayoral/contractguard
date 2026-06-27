package com.contractguard.e2e;

import com.contractguard.adapter.process.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The complete seeded demo (§6, acceptance criteria §23) against the real
 * application: real Git repository, real patch application, real
 * {@code mvnw verify} — only the LLM is the deterministic scripted gateway.
 * Runs in the {@code e2e} Maven profile.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "contractguard.workspace.roots[0]=target/e2e-workspace",
        "contractguard.storage.directory=target/e2e-data",
        "contractguard.specs.directory=../samples/openapi",
        "contractguard.llm.provider=mock",
        "contractguard.validation.timeout=10m"
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoE2ETest {

    private static final Path WORKSPACE = Path.of("target/e2e-workspace");
    private static final Path REPO = WORKSPACE.resolve("customer-consumer");
    private static final Path TEMPLATE = Path.of("../samples/customer-consumer");

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcessRunner processRunner = new ProcessRunner();

    private static String runId;
    private static String planHash;

    @BeforeAll
    void resetDemoRepository() throws IOException {
        if (Files.exists(WORKSPACE)) {
            try (Stream<Path> paths = Files.walk(WORKSPACE)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
        Files.createDirectories(WORKSPACE);
        copyTree(TEMPLATE, REPO);
        git("init", "-q", "-b", "main");
        git("add", "-A");
        git("-c", "user.name=Demo Consumer", "-c", "user.email=demo@contractguard.local",
                "commit", "-q", "-m", "Initial consumer state");
    }

    @Test
    @Order(1)
    void analysisDetectsTheSeededScenario() throws Exception {
        ResponseEntity<String> created = rest.postForEntity("/api/runs",
                json("""
                        {"name":"e2e-demo","repositoryId":"customer-consumer",
                         "oldSpec":"customer-api-v1.yaml","newSpec":"customer-api-v2.yaml"}"""),
                String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        runId = mapper.readTree(created.getBody()).path("id").asText();

        JsonNode run = awaitState("AWAITING_APPROVAL", Duration.ofSeconds(90));

        // All four seeded changes with the expected classifications (§6.4).
        JsonNode changes = run.path("changes");
        assertThat(changes).hasSize(4);
        Map<String, String> byType = new java.util.HashMap<>();
        changes.forEach(change ->
                byType.put(change.path("type").asText(), change.path("classification").asText()));
        assertThat(byType).containsEntry("ENDPOINT_RENAMED", "BREAKING")
                .containsEntry("PROPERTY_RENAMED", "BREAKING")
                .containsEntry("ENUM_VALUE_REMOVED", "BREAKING")
                .containsEntry("PROPERTY_ADDED", "NON_BREAKING");
        changes.forEach(change ->
                assertThat(change.path("explanation").asText()).isNotBlank());

        // Known impacted files with line evidence (§23.5).
        Set<String> evidencePaths = new java.util.HashSet<>();
        run.path("evidence").forEach(evidence -> {
            evidencePaths.add(evidence.path("relativePath").asText());
            assertThat(evidence.path("startLine").asInt()).isPositive();
            assertThat(evidence.path("snippet").asText()).isNotBlank();
        });
        assertThat(evidencePaths).contains(
                "src/main/java/com/example/customerapp/CustomerClient.java",
                "src/main/java/com/example/customerapp/Customer.java",
                "src/main/java/com/example/customerapp/CustomerStatus.java",
                "src/main/java/com/example/customerapp/OrderEligibilityPolicy.java",
                "src/test/java/com/example/customerapp/CustomerClientTest.java",
                "src/test/java/com/example/customerapp/OrderEligibilityPolicyTest.java",
                "README.md");

        // Assessments cite evidence (§23.6).
        assertThat(run.path("assessments").size()).isGreaterThanOrEqualTo(3);
        run.path("assessments").forEach(assessment ->
                assertThat(assessment.path("evidenceIds").size()).isPositive());

        // The plan lists files, tests, risks and validation (§23.7).
        JsonNode plan = run.path("plan");
        planHash = plan.path("hash").asText();
        assertThat(planHash).isNotBlank();
        assertThat(plan.path("items").size()).isEqualTo(3);
        plan.path("items").forEach(item -> {
            assertThat(item.path("expectedFiles").size()).isPositive();
            assertThat(item.path("risk").asText()).isNotBlank();
            assertThat(item.path("rollback").asText()).isNotBlank();
            assertThat(item.path("validationCommand").asText()).isEqualTo("maven-verify");
            assertThat(item.path("evidenceIds").size()).isPositive();
        });
    }

    @Test
    @Order(2)
    void modificationIsImpossibleBeforeApproval() {
        ResponseEntity<String> execute = rest.postForEntity(
                "/api/runs/%s/execute".formatted(runId), null, String.class);
        assertThat(execute.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> staleApproval = rest.postForEntity(
                "/api/runs/%s/approval".formatted(runId),
                json("{\"decision\":\"APPROVED\",\"planHash\":\"stale-hash\"}"), String.class);
        assertThat(staleApproval.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // The repository is untouched: still on main, still clean.
        assertThat(gitOutput("status", "--porcelain")).isBlank();
        assertThat(gitOutput("rev-parse", "--abbrev-ref", "HEAD").strip()).isEqualTo("main");
    }

    @Test
    @Order(3)
    void approvedRunRemediatesAndPassesMavenVerify() throws Exception {
        ResponseEntity<String> approval = rest.postForEntity(
                "/api/runs/%s/approval".formatted(runId),
                json("{\"decision\":\"APPROVED\",\"planHash\":\"%s\"}".formatted(planHash)),
                String.class);
        assertThat(approval.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> execute = rest.postForEntity(
                "/api/runs/%s/execute".formatted(runId), null, String.class);
        assertThat(execute.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        JsonNode run = awaitState("SUCCEEDED", Duration.ofMinutes(10));

        assertThat(run.path("originalBranch").asText()).isEqualTo("main");
        assertThat(run.path("workingBranch").asText()).startsWith("contractguard/run-");
        assertThat(run.path("validations").get(0).path("successful").asBoolean()).isTrue();
        assertThat(run.path("validations").get(0).path("summary").asText()).contains("BUILD SUCCESS");
        assertThat(run.path("patches").get(0).path("checkStatus").asText()).isEqualTo("APPLIED");

        // Only approved files changed (§23.12).
        Set<String> approvedFiles = new java.util.HashSet<>();
        run.path("plan").path("approvedFiles").forEach(file -> approvedFiles.add(file.asText()));
        // ProcessRunner merges stderr; drop git's line-ending advice lines.
        Set<String> changedFiles = gitOutput("diff", "--name-only", "main").lines()
                .filter(line -> !line.isBlank() && !line.startsWith("warning:"))
                .collect(Collectors.toSet());
        assertThat(changedFiles).isNotEmpty().isSubsetOf(approvedFiles);

        // The original branch is preserved and identical to the initial commit (§23.20 / §6.4).
        assertThat(gitOutput("rev-parse", "--abbrev-ref", "HEAD").strip())
                .isEqualTo(run.path("workingBranch").asText());
        assertThat(gitOutput("branch", "--list", "main")).contains("main");
        assertThat(gitOutput("log", "main", "--oneline").lines().count()).isEqualTo(1);
        // No remote interaction ever happens (§23.20): the repo has no remotes.
        assertThat(gitOutput("remote")).isBlank();

        // The remediated consumer no longer references the old contract.
        String customer = Files.readString(
                REPO.resolve("src/main/java/com/example/customerapp/Customer.java"));
        assertThat(customer).contains("displayName").doesNotContain("fullName");
        String status = Files.readString(
                REPO.resolve("src/main/java/com/example/customerapp/CustomerStatus.java"));
        assertThat(status).doesNotContain("SUSPENDED");
        String client = Files.readString(
                REPO.resolve("src/main/java/com/example/customerapp/CustomerClient.java"));
        assertThat(client).contains("/v2/customers/{id}");
    }

    @Test
    @Order(4)
    void reportsAndTimelineAreComplete() throws Exception {
        ResponseEntity<String> markdown = rest.getForEntity(
                "/api/runs/%s/artifacts/report.md".formatted(runId), String.class);
        assertThat(markdown.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String section : new String[] {"## Run", "## Detected changes", "## Impact evidence",
                "## Impact assessments", "## Migration plan", "## Approval", "## Patches",
                "## Validation", "## Outcome", "## Limitations", "## Trace"}) {
            assertThat(markdown.getBody()).contains(section);
        }
        assertThat(markdown.getBody()).contains("SUCCEEDED").contains("BUILD SUCCESS");

        JsonNode report = mapper.readTree(rest.getForEntity(
                "/api/runs/%s/artifacts/report.json".formatted(runId), String.class).getBody());
        assertThat(report.path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(report.path("changes")).hasSize(4);
        assertThat(report.path("approval").path("decision").asText()).isEqualTo("APPROVED");
        assertThat(report.path("limitations").size()).isPositive();

        JsonNode events = mapper.readTree(rest.getForEntity(
                "/api/runs/%s/events/list".formatted(runId), String.class).getBody());
        Set<String> steps = new java.util.HashSet<>();
        events.forEach(event -> steps.add(event.path("step").asText()));
        assertThat(steps).contains("input-validation", "diff", "change-explainer", "search",
                "assessment", "planning", "approval", "branch", "patch", "validation", "run");

        // Run history lists the run for reopening (FR-021).
        JsonNode history = mapper.readTree(rest.getForEntity("/api/runs", String.class).getBody());
        assertThat(history.findValues("id")).anySatisfy(id ->
                assertThat(id.asText()).isEqualTo(runId));
    }

    private JsonNode awaitState(String expected, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            last = mapper.readTree(rest.getForEntity(
                    "/api/runs/" + runId, String.class).getBody());
            String state = last.path("state").asText();
            if (expected.equals(state)) {
                return last;
            }
            if (Set.of("FAILED", "REJECTED", "CANCELLED").contains(state)) {
                throw new AssertionError("run ended in %s instead of %s: %s"
                        .formatted(state, expected, last.path("failure")));
            }
            Thread.sleep(500);
        }
        throw new AssertionError("run did not reach %s in time; last: %s".formatted(expected, last));
    }

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private void git(String... args) {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessRunner.ProcessResult result = processRunner.run(
                command, REPO, Map.of(), Duration.ofSeconds(30), 200_000);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git setup failed: " + result.output());
        }
    }

    private String gitOutput(String... args) {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessRunner.ProcessResult result = processRunner.run(
                command, REPO, Map.of(), Duration.ofSeconds(30), 200_000);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git query failed: " + result.output());
        }
        return result.output();
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            paths.forEach(path -> {
                try {
                    Path destination = target.resolve(source.relativize(path).toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination);
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }
}
