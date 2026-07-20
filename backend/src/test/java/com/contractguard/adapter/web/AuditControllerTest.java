package com.contractguard.adapter.web;

import com.contractguard.application.service.AuditTrailService;
import com.contractguard.domain.AuditEntry;
import com.contractguard.domain.AuditEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuditController.class)
class AuditControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AuditTrailService audit;

    private static final AuditEntry ENTRY = new AuditEntry("audit-1", "run-1", "customer-consumer",
            "local-operator", AuditEventType.APPROVAL_DECISION, "Plan approved", "hash-1",
            Instant.parse("2026-07-20T10:00:00Z"));

    @Test
    void listsAllEntriesWithoutFilters() throws Exception {
        when(audit.findAll()).thenReturn(List.of(ENTRY));

        mvc.perform(get("/api/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("audit-1"))
                .andExpect(jsonPath("$[0].eventType").value("APPROVAL_DECISION"))
                .andExpect(jsonPath("$[0].planHash").value("hash-1"));
        verify(audit).findAll();
        verifyNoMoreInteractions(audit);
    }

    @Test
    void filtersByRunId() throws Exception {
        when(audit.findByRun("run-1")).thenReturn(List.of(ENTRY));

        mvc.perform(get("/api/audit").param("runId", "run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runId").value("run-1"));
        verify(audit).findByRun("run-1");
        verifyNoMoreInteractions(audit);
    }

    @Test
    void filtersByRepositoryIdWhenNoRunIdGiven() throws Exception {
        when(audit.findByRepository("customer-consumer")).thenReturn(List.of(ENTRY));

        mvc.perform(get("/api/audit").param("repositoryId", "customer-consumer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].repositoryId").value("customer-consumer"));
        verify(audit).findByRepository("customer-consumer");
        verifyNoMoreInteractions(audit);
    }
}
