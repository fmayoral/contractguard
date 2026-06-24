package com.contractguard.application.service;

import com.contractguard.adapter.json.JacksonJsonCodec;
import com.contractguard.application.port.ArtifactStore;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.Approval;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.Fixtures;
import com.contractguard.domain.RunState;
import com.contractguard.testsupport.InMemoryRunEventLog;
import com.contractguard.testsupport.InMemoryRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportServiceTest {

    private InMemoryRunRepository runs;
    private InMemoryRunEventLog events;
    private final Map<String, String> stored = new HashMap<>();
    private ReportService service;

    @BeforeEach
    void setUp() {
        runs = new InMemoryRunRepository();
        events = new InMemoryRunEventLog();
        ArtifactStore artifacts = new ArtifactStore() {
            @Override
            public String save(String runId, String name, String content) {
                stored.put(name, content);
                return name;
            }

            @Override
            public Optional<String> read(String runId, String artifactId) {
                return Optional.ofNullable(stored.get(artifactId));
            }
        };
        service = new ReportService(runs, events, artifacts, new JacksonJsonCodec());
    }

    private AnalysisRun completedRun() {
        AnalysisRun run = Fixtures.runAwaitingApproval();
        run.recordApproval(new Approval(run.id(), run.plan().orElseThrow().hash(),
                Approval.Decision.APPROVED, Fixtures.T0), Fixtures.T0);
        run.transitionTo(RunState.PREPARING_BRANCH, Fixtures.T0);
        run.recordBranches("main", "contractguard/run-run", Fixtures.T0);
        run.transitionTo(RunState.PATCHING, Fixtures.T0);
        run.recordPatch(Fixtures.patch("p-1", 1), Fixtures.T0);
        run.markPatchApplied("p-1", Fixtures.T0);
        run.transitionTo(RunState.VALIDATING, Fixtures.T0);
        run.recordValidation(Fixtures.validation(1, true), Fixtures.T0);
        run.transitionTo(RunState.SUCCEEDED, Fixtures.T0);
        run.recordSpecs("v1.yaml", "v2.yaml", "hash-old", "hash-new", Fixtures.T0);
        runs.save(run);
        events.append(run.id(), "diff", "COMPLETED", "1 change(s) detected", null);
        return run;
    }

    @Test
    void markdownContainsEveryRequiredSection() {
        AnalysisRun run = completedRun();
        run.attachExplanation("ch-1", "Consumers reading fullName receive null.", Fixtures.T0);
        runs.save(run);

        String markdown = service.markdownReport(run.id());

        for (String section : new String[] {"## Run", "## Detected changes", "## Impact evidence",
                "## Impact assessments", "## Migration plan", "## Approval", "## Patches",
                "## Validation", "## Outcome", "## Limitations", "## Trace"}) {
            assertThat(markdown).contains(section);
        }
        assertThat(markdown)
                .contains(run.id())
                .contains("hash-old").contains("hash-new")
                .contains("contractguard/run-run")
                .contains("PROPERTY_RENAMED")
                .contains("src/main/java/App.java")
                .contains("APPROVED")
                .contains("BUILD SUCCESS")
                .contains("SUCCEEDED")
                .contains("Consumers reading fullName receive null.")
                .contains("1 change(s) detected");
        assertThat(stored).containsKey("report.md");
    }

    @Test
    void jsonReportIsParseableAndComplete() throws Exception {
        AnalysisRun run = completedRun();

        String json = service.jsonReport(run.id());

        JsonNode root = new ObjectMapper().readTree(json);
        assertThat(root.path("runId").asText()).isEqualTo(run.id());
        assertThat(root.path("state").asText()).isEqualTo("SUCCEEDED");
        assertThat(root.path("specifications").path("old").path("sha256").asText()).isEqualTo("hash-old");
        assertThat(root.path("changes")).hasSize(1);
        assertThat(root.path("evidence")).hasSize(1);
        assertThat(root.path("assessments")).hasSize(1);
        assertThat(root.path("plan").path("items")).hasSize(1);
        assertThat(root.path("approval").path("decision").asText()).isEqualTo("APPROVED");
        assertThat(root.path("patches")).hasSize(1);
        assertThat(root.path("validations")).hasSize(1);
        assertThat(root.path("limitations").size()).isGreaterThan(2);
        assertThat(root.path("events")).hasSize(1);
        assertThat(stored).containsKey("report.json");
    }

    @Test
    void failedRunReportStatesMutationAndRemediation() {
        AnalysisRun run = Fixtures.newRun();
        run.markFailed(new com.contractguard.domain.RunFailure(
                com.contractguard.domain.FailureCategory.DIRTY_REPOSITORY,
                "repo dirty", false, null, "Commit or stash first"), Fixtures.T0);
        runs.save(run);

        String markdown = service.markdownReport(run.id());

        assertThat(markdown).contains("DIRTY_REPOSITORY")
                .contains("Repository mutated: no")
                .contains("Commit or stash first")
                .contains("_No plan generated._");
    }

    @Test
    void unknownRunFailsTyped() {
        assertThatThrownBy(() -> service.markdownReport("ghost"))
                .isInstanceOf(ContractGuardException.class);
    }
}
