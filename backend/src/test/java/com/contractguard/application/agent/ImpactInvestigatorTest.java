package com.contractguard.application.agent;

import com.contractguard.adapter.json.JacksonJsonCodec;
import com.contractguard.application.llm.LlmJsonClient;
import com.contractguard.application.llm.PromptLibrary;
import com.contractguard.application.port.RepositorySearchPort;
import com.contractguard.application.port.SourceReaderPort;
import com.contractguard.domain.ApiChange;
import com.contractguard.domain.ChangeType;
import com.contractguard.domain.Classification;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.ImpactAssessment;
import com.contractguard.domain.ImpactEvidence;
import com.contractguard.testsupport.InMemoryRunEventLog;
import com.contractguard.testsupport.NoOpObservability;
import com.contractguard.testsupport.QueuedLlmGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImpactInvestigatorTest {

    private static final String FINISH = """
            {"action":"finish","search":null,"read":null,"assessments":[
              {"apiChangeId":"c1","component":"src/A.java","severity":"HIGH","confidence":"HIGH",
               "failureMode":"404s","recommendedAction":"update","assumptions":[],
               "evidenceIds":["ev-1"]}]}""";

    private final JacksonJsonCodec codec = new JacksonJsonCodec();
    private final PromptLibrary prompts = new PromptLibrary();
    private final InMemoryRunEventLog events = new InMemoryRunEventLog();

    private final RepositorySearchPort searchPort = (repositoryId, query, glob, maxResults) ->
            List.of(new RepositorySearchPort.SearchMatch("src/A.java", 5, "uses " + query));
    private final SourceReaderPort readerPort = (repositoryId, path, start, end) ->
            new SourceReaderPort.FileContent(path, "file body of " + path, 1, 3, 3, false);

    private ImpactInvestigator investigator(QueuedLlmGateway gateway, int maxSteps) {
        return new ImpactInvestigator(new LlmJsonClient(gateway, codec, new NoOpObservability()), prompts, codec,
                searchPort, readerPort, maxSteps);
    }

    private static ApiChange change() {
        return new ApiChange("c1", ChangeType.PROPERTY_RENAMED, Classification.BREAKING,
                null, null, "Customer", "fullName", "fullName", "displayName", "REASON", "{}", null);
    }

    private static ImpactEvidence evidence() {
        return new ImpactEvidence("ev-1", "c1", "src/A.java", 5, 5, "uses fullName",
                "fullName", "READS_RENAMED_PROPERTY", "h");
    }

    @Test
    void runsToolsThenFinishesWithMappedAssessments() {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue(
                "{\"action\":\"search_repository\",\"search\":{\"query\":\"fullName\",\"glob\":null,"
                        + "\"maxResults\":10},\"read\":null,\"assessments\":null}",
                "{\"action\":\"read_source_file\",\"read\":{\"path\":\"src/A.java\","
                        + "\"startLine\":1,\"endLine\":10},\"search\":null,\"assessments\":null}",
                FINISH);

        List<ImpactAssessment> assessments = investigator(gateway, 5)
                .investigate("run-1", "demo", List.of(change()), List.of(evidence()), events);

        assertThat(assessments).singleElement().satisfies(assessment -> {
            assertThat(assessment.apiChangeId()).isEqualTo("c1");
            assertThat(assessment.evidenceIds()).containsExactly("ev-1");
        });
        // Tool results flow back into the next request payload.
        assertThat(gateway.requests().get(1).userPayload()).contains("uses fullName");
        assertThat(gateway.requests().get(2).userPayload()).contains("file body of src/A.java");
        // Tool calls are traced as events.
        assertThat(events.all()).extracting(e -> e.status()).contains("TOOL");
    }

    @Test
    void unregisteredToolIsRejectedWithRetryThenTypedFailure() {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue(
                "{\"action\":\"delete_repository\",\"search\":null,\"read\":null,\"assessments\":null}",
                "{\"action\":\"format_disk\",\"search\":null,\"read\":null,\"assessments\":null}");
        ImpactInvestigator investigator = investigator(gateway, 5);
        List<ApiChange> changes = List.of(change());
        List<ImpactEvidence> evidenceList = List.of(evidence());

        assertThatThrownBy(() -> investigator.investigate("run-1", "demo", changes, evidenceList, events))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.INVALID_LLM_RESPONSE));
        assertThat(gateway.requests().get(1).userPayload()).contains("not a registered tool");
    }

    @Test
    void assessmentsCitingUnknownEvidenceAreRejected() {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue(
                FINISH.replace("ev-1", "ev-fabricated"),
                FINISH.replace("ev-1", "ev-fabricated"));
        ImpactInvestigator investigator = investigator(gateway, 5);
        List<ApiChange> changes = List.of(change());
        List<ImpactEvidence> evidenceList = List.of(evidence());

        assertThatThrownBy(() -> investigator.investigate("run-1", "demo", changes, evidenceList, events))
                .isInstanceOf(ContractGuardException.class);
        assertThat(gateway.requests().get(1).userPayload()).contains("unknown evidence");
    }

    @Test
    void invalidSeverityIsRejected() {
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue(
                FINISH.replace("\"HIGH\"", "\"CATASTROPHIC\""),
                FINISH);

        List<ImpactAssessment> assessments = investigator(gateway, 5)
                .investigate("run-1", "demo", List.of(change()), List.of(evidence()), events);

        assertThat(assessments).hasSize(1);
        assertThat(gateway.requests().get(1).userPayload()).contains("invalid severity");
    }

    @Test
    void stepBudgetExhaustionFailsTyped() {
        String search = "{\"action\":\"search_repository\",\"search\":{\"query\":\"x\",\"glob\":null,"
                + "\"maxResults\":5},\"read\":null,\"assessments\":null}";
        QueuedLlmGateway gateway = new QueuedLlmGateway().enqueue(search, search);
        ImpactInvestigator investigator = investigator(gateway, 2);
        List<ApiChange> changes = List.of(change());
        List<ImpactEvidence> evidenceList = List.of(evidence());

        assertThatThrownBy(() -> investigator.investigate("run-1", "demo", changes, evidenceList, events))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.POLICY_VIOLATION));
    }
}
