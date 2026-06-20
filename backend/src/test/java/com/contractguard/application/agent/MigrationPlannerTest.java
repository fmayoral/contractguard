package com.contractguard.application.agent;

import com.contractguard.adapter.json.JacksonJsonCodec;
import com.contractguard.application.llm.LlmJsonClient;
import com.contractguard.application.llm.PromptLibrary;
import com.contractguard.application.policy.WorkspacePolicy;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.Fixtures;
import com.contractguard.domain.ImpactEvidence;
import com.contractguard.domain.MigrationPlan;
import com.contractguard.domain.PlanHasher;
import com.contractguard.domain.RunState;
import com.contractguard.testsupport.QueuedLlmGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationPlannerTest {

    private static final String VALID_PLAN = """
            {"items":[{"objective":"Rename field","expectedFiles":["src/main/java/App.java"],
              "proposedAction":"rename","testsToUpdate":["src/test/java/AppTest.java"],
              "validationCommand":"maven-verify","risk":"low","rollback":"discard branch",
              "evidenceIds":["ev-1"]}]}""";

    private final JacksonJsonCodec codec = new JacksonJsonCodec();
    private final PromptLibrary prompts = new PromptLibrary();

    @TempDir
    Path workspace;

    private MigrationPlanner planner(QueuedLlmGateway gateway) throws Exception {
        Files.createDirectories(workspace.resolve("any/.git"));
        return new MigrationPlanner(new LlmJsonClient(gateway, codec), prompts, codec,
                new WorkspacePolicy(List.of(workspace)), List.of("maven-verify"), Clock.systemUTC());
    }

    /**
     * Fixtures.runAwaitingApproval uses evidence path src/main/java/App.java and
     * test path src/test/java/AppTest.java via a second evidence record.
     */
    private AnalysisRun runReadyForPlanning() {
        AnalysisRun run = Fixtures.newRun();
        run.transitionTo(RunState.VALIDATING_INPUT, Fixtures.T0);
        run.transitionTo(RunState.DIFFING, Fixtures.T0);
        run.recordChanges(List.of(Fixtures.change("ch-1")), Fixtures.T0);
        run.transitionTo(RunState.SEARCHING, Fixtures.T0);
        run.recordEvidence(List.of(
                Fixtures.evidence("ev-1", "ch-1"),
                new ImpactEvidence("ev-2", "ch-1",
                        "src/test/java/AppTest.java", 4, 4, "snippet", "fullName", "TESTS_PROPERTY", "h")),
                Fixtures.T0);
        run.transitionTo(RunState.ASSESSING, Fixtures.T0);
        run.recordAssessments(List.of(Fixtures.assessment("as-1", "ch-1", List.of("ev-1"))), Fixtures.T0);
        run.transitionTo(RunState.PLANNING, Fixtures.T0);
        return run;
    }

    @Test
    void mapsDraftToDomainPlanWithHash() throws Exception {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue(VALID_PLAN);

        MigrationPlan plan = planner(gateway).plan(runReadyForPlanning());

        assertThat(plan.version()).isEqualTo(1);
        assertThat(plan.items()).hasSize(1);
        assertThat(plan.items().get(0).expectedFiles()).containsExactly("src/main/java/App.java");
        assertThat(plan.hash()).isEqualTo(PlanHasher.hash(plan.items()));
        assertThat(plan.approvedFiles()).contains("src/main/java/App.java", "src/test/java/AppTest.java");
    }

    @Test
    void filesWithoutEvidenceAreRejected() throws Exception {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue(
                VALID_PLAN.replace("src/main/java/App.java", "src/main/java/Sneaky.java"),
                VALID_PLAN.replace("src/main/java/App.java", "src/main/java/Sneaky.java"));

        assertThatThrownBy(() -> planner(gateway).plan(runReadyForPlanning()))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.INVALID_LLM_RESPONSE));
        assertThat(gateway.requests().get(1).userPayload()).contains("not backed by evidence");
    }

    @Test
    void nonAllowlistedValidationCommandIsRejected() throws Exception {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue(
                VALID_PLAN.replace("maven-verify", "rm -rf /"),
                VALID_PLAN);

        MigrationPlan plan = planner(gateway).plan(runReadyForPlanning());

        assertThat(plan.items()).hasSize(1);
        assertThat(gateway.requests().get(1).userPayload()).contains("not allow-listed");
    }

    @Test
    void emptyPlanMeansNothingActionable() throws Exception {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue("{\"items\":[]}");

        assertThatThrownBy(() -> planner(gateway).plan(runReadyForPlanning()))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.UNSUPPORTED_FEATURE));
    }
}
