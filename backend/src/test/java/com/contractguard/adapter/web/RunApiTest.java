package com.contractguard.adapter.web;

import com.contractguard.application.service.ApprovalService;
import com.contractguard.application.service.ExecutionService;
import com.contractguard.application.service.PublishService;
import com.contractguard.application.service.RemoteRepositoryService;
import com.contractguard.application.service.ReportService;
import com.contractguard.application.service.RunQueryService;
import com.contractguard.application.service.RunService;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.Approval;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.Fixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({RunController.class, RunEventsController.class})
class RunApiTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RunService runService;

    @MockBean
    private RunQueryService queries;

    @MockBean
    private ApprovalService approvals;

    @MockBean
    private ExecutionService executions;

    @MockBean
    private PublishService publishing;

    @MockBean
    private RemoteRepositoryService remoteRepositories;

    @MockBean
    private ReportService reports;

    @MockBean
    private ExecutorService executor;

    @Test
    void createRunReturns201WithSummary() throws Exception {
        AnalysisRun run = Fixtures.newRun();
        when(runService.createRun("demo", "customer-consumer", "v1.yaml", "v2.yaml")).thenReturn(run);

        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"demo","repositoryId":"customer-consumer",
                                 "oldSpec":"v1.yaml","newSpec":"v2.yaml"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(run.id()))
                .andExpect(jsonPath("$.state").value("CREATED"));
    }

    @Test
    void invalidCreateRequestGets400ProblemDetail() throws Exception {
        mvc.perform(post("/api/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void unknownRunGets404ProblemDetail() throws Exception {
        when(queries.getRun("ghost")).thenThrow(ContractGuardException.of(
                FailureCategory.NOT_FOUND, "run not found: ghost", "Check the run ID."));

        mvc.perform(get("/api/runs/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.category").value("NOT_FOUND"))
                .andExpect(jsonPath("$.mutationOccurred").value(false))
                .andExpect(jsonPath("$.remediation").value("Check the run ID."));
    }

    @Test
    void runDetailExposesChangesEvidenceAndPlan() throws Exception {
        AnalysisRun run = Fixtures.runAwaitingApproval();
        when(queries.getRun(run.id())).thenReturn(run);

        mvc.perform(get("/api/runs/" + run.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.changes[0].type").value("PROPERTY_RENAMED"))
                .andExpect(jsonPath("$.evidence[0].relativePath").value("src/main/java/App.java"))
                .andExpect(jsonPath("$.assessments[0].evidenceIds[0]").value("ev-1"))
                .andExpect(jsonPath("$.plan.hash").isNotEmpty())
                .andExpect(jsonPath("$.plan.approvedFiles").isArray());
    }

    @Test
    void approvalMismatchGets409() throws Exception {
        when(approvals.decide(eq("run-1"), eq(Approval.Decision.APPROVED), anyString()))
                .thenThrow(ContractGuardException.of(FailureCategory.APPROVAL_MISMATCH,
                        "approval hash stale", "Reload the plan."));

        mvc.perform(post("/api/runs/run-1/approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\",\"planHash\":\"stale\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.category").value("APPROVAL_MISMATCH"));
    }

    @Test
    void invalidDecisionGets409() throws Exception {
        mvc.perform(post("/api/runs/run-1/approval")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"MAYBE\",\"planHash\":\"h\"}"))
                .andExpect(status().isConflict());
        verify(approvals, never()).decide(anyString(), any(), anyString());
    }

    @Test
    void executeWithoutApprovalGets409AndNothingRuns() throws Exception {
        when(executions.beginExecution("run-1")).thenThrow(ContractGuardException.of(
                FailureCategory.ILLEGAL_STATE, "no recorded approval", "Approve first."));

        mvc.perform(post("/api/runs/run-1/execute"))
                .andExpect(status().isConflict());
        verify(executor, never()).execute(any());
    }

    @Test
    void executeAfterApprovalIsAcceptedAndScheduled() throws Exception {
        AnalysisRun run = Fixtures.runAwaitingApproval();
        when(executions.beginExecution(run.id())).thenReturn(run);

        mvc.perform(post("/api/runs/" + run.id() + "/execute"))
                .andExpect(status().isAccepted());
        verify(executor).execute(any());
    }

    @Test
    void publishForUnregisteredRepositoryGets400() throws Exception {
        when(publishing.beginPublish("run-1")).thenThrow(ContractGuardException.of(
                FailureCategory.REMOTE_REPOSITORY_NOT_REGISTERED,
                "repository 'customer-consumer' is not registered for remote publishing",
                "Register the repository via POST /api/repositories/remote first."));

        mvc.perform(post("/api/runs/run-1/publish"))
                .andExpect(status().isBadRequest());
        verify(executor, never()).execute(any());
    }

    @Test
    void publishAfterSuccessIsAcceptedAndScheduled() throws Exception {
        AnalysisRun run = Fixtures.runAwaitingApproval();
        when(publishing.beginPublish(run.id())).thenReturn(run);

        mvc.perform(post("/api/runs/" + run.id() + "/publish"))
                .andExpect(status().isAccepted());
        verify(executor).execute(any());
    }

    @Test
    void planEndpointReturns404BeforePlanning() throws Exception {
        when(queries.getRun("run-1")).thenReturn(Fixtures.newRun());

        mvc.perform(get("/api/runs/run-1/plan"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportsAreServedWithProperContentTypes() throws Exception {
        when(reports.markdownReport("run-1")).thenReturn("# ContractGuard Report");
        when(reports.jsonReport("run-1")).thenReturn("{\"runId\":\"run-1\"}");

        mvc.perform(get("/api/runs/run-1/artifacts/report.md"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().string("# ContractGuard Report"));
        mvc.perform(get("/api/runs/run-1/artifacts/report.json"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"runId\":\"run-1\"}"));
    }

    @Test
    void arbitraryArtifactsAreServedOr404() throws Exception {
        when(queries.readArtifact("run-1", "validation-attempt-1.log"))
                .thenReturn(java.util.Optional.of("BUILD SUCCESS"));
        when(queries.readArtifact("run-1", "missing"))
                .thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/runs/run-1/artifacts/validation-attempt-1.log"))
                .andExpect(status().isOk())
                .andExpect(content().string("BUILD SUCCESS"));
        mvc.perform(get("/api/runs/run-1/artifacts/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void setupListsLocalAndRemoteRepositoriesSeparately() throws Exception {
        when(runService.listRepositories()).thenReturn(List.of("customer-consumer"));
        when(runService.listSpecificationFiles()).thenReturn(List.of("v1.yaml", "v2.yaml"));
        when(runService.listRemoteRepositories()).thenReturn(List.of("acme-widgets"));

        mvc.perform(get("/api/setup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositories[0]").value("customer-consumer"))
                .andExpect(jsonPath("$.specifications", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.remoteRepositories[0]").value("acme-widgets"));
    }

    @Test
    void listRunsReturnsSummaries() throws Exception {
        when(queries.listRuns()).thenReturn(List.of(Fixtures.newRun()));

        mvc.perform(get("/api/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("run-1"));
    }

    @Test
    void runDetailFlagsWhenTheRepositoryIsRemoteRegistered() throws Exception {
        AnalysisRun run = Fixtures.runAwaitingApproval();
        when(queries.getRun(run.id())).thenReturn(run);
        when(remoteRepositories.isRemote(run.repositoryId())).thenReturn(true);

        mvc.perform(get("/api/runs/" + run.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remoteRepository").value(true));
    }

    @Test
    void unexpectedErrorsBecome500ProblemDetails() throws Exception {
        when(queries.listRuns()).thenThrow(new IllegalStateException("database exploded"));

        mvc.perform(get("/api/runs"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.category").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("unexpected server error"));
    }
}
