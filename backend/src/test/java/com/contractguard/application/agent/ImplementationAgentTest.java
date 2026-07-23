package com.contractguard.application.agent;

import com.contractguard.adapter.json.JacksonJsonCodec;
import com.contractguard.application.llm.LlmJsonClient;
import com.contractguard.application.llm.PromptLibrary;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.Fixtures;
import com.contractguard.domain.MigrationPlan;
import com.contractguard.testsupport.NoOpObservability;
import com.contractguard.testsupport.QueuedLlmGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImplementationAgentTest {

    private final JacksonJsonCodec codec = new JacksonJsonCodec();
    private final MigrationPlan plan = Fixtures.plan(List.of(Fixtures.planItem("it-1")));
    private final Map<String, String> files = Map.of("src/main/java/App.java", "String fullName;");

    private ImplementationAgent agent(QueuedLlmGateway gateway) {
        return new ImplementationAgent(new LlmJsonClient(gateway, codec, new NoOpObservability()), new PromptLibrary(), codec);
    }

    @Test
    void returnsProposedRewritesForApprovedFiles() {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue("""
                {"files":[{"path":"src/main/java/App.java","newContent":"String displayName;"}],
                 "notes":"renamed"}""");

        Map<String, String> result = agent(gateway).propose(
                ImplementationAgent.IMPLEMENTATION_PROMPT,
                List.of(Fixtures.change("ch-1")), plan, files, null, false);

        assertThat(result).containsEntry("src/main/java/App.java", "String displayName;");
        assertThat(gateway.requests().get(0).promptName()).isEqualTo("implementation-agent");
    }

    @Test
    void failureOutputIsForwardedOnRepair() {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue("""
                {"files":[{"path":"src/main/java/App.java","newContent":"fixed"}],"notes":"n"}""");

        agent(gateway).propose(ImplementationAgent.REPAIR_PROMPT,
                List.of(Fixtures.change("ch-1")), plan, files, "COMPILATION ERROR at line 3", false);

        assertThat(gateway.requests().get(0).promptName()).isEqualTo("repair-agent");
        assertThat(gateway.requests().get(0).userPayload()).contains("COMPILATION ERROR at line 3");
    }

    @Test
    void pathsOutsideTheApprovedSetAreRejected() {
        String sneaky = """
                {"files":[{"path":"src/main/java/Backdoor.java","newContent":"x"}],"notes":"n"}""";
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue(sneaky, sneaky);

        assertThatThrownBy(() -> agent(gateway).propose(
                ImplementationAgent.IMPLEMENTATION_PROMPT,
                List.of(Fixtures.change("ch-1")), plan, files, null, false))
                .isInstanceOf(ContractGuardException.class);
        assertThat(gateway.requests().get(1).userPayload()).contains("not in the approved set");
    }

    @Test
    void emptyProposalsAreRejectedByDefault() {
        String empty = "{\"files\":[],\"notes\":\"nothing\"}";
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue(empty, empty);

        assertThatThrownBy(() -> agent(gateway).propose(
                ImplementationAgent.IMPLEMENTATION_PROMPT,
                List.of(Fixtures.change("ch-1")), plan, files, null, false))
                .isInstanceOf(ContractGuardException.class);
    }

    @Test
    void emptyProposalsAreAcceptedWhenDeterministicChangesWereAlreadyMade() {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue(
                "{\"files\":[],\"notes\":\"nothing further needed\"}");

        Map<String, String> result = agent(gateway).propose(
                ImplementationAgent.IMPLEMENTATION_PROMPT,
                List.of(Fixtures.change("ch-1")), plan, files, null, true);

        assertThat(result).isEmpty();
    }
}
