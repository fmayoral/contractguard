package com.contractguard.adapter.web;

import com.contractguard.application.service.SpecPreviewService;
import com.contractguard.domain.ApiChange;
import com.contractguard.domain.ChangeType;
import com.contractguard.domain.Classification;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SpecDiffController.class)
class SpecDiffControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SpecPreviewService preview;

    @Test
    void returnsTheClassifiedChangesAndWarnings() throws Exception {
        ApiChange change = new ApiChange("chg-1", ChangeType.ENDPOINT_RENAMED, Classification.BREAKING,
                "GET", "/customers/{id}", "Customer", null, "/customers/{id}", "/v2/customers/{id}",
                "ENDPOINT_PATH_CHANGED", "{}", null);
        when(preview.preview("local:v1.yaml", "local:v2.yaml"))
                .thenReturn(new SpecPreviewService.Preview(List.of(change), List.of("one limitation")));

        mvc.perform(get("/api/spec-diff").param("oldSpec", "local:v1.yaml").param("newSpec", "local:v2.yaml"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].type").value("ENDPOINT_RENAMED"))
                .andExpect(jsonPath("$.changes[0].classification").value("BREAKING"))
                .andExpect(jsonPath("$.changes[0].explanation").doesNotExist())
                .andExpect(jsonPath("$.warnings[0]").value("one limitation"));
        verify(preview).preview("local:v1.yaml", "local:v2.yaml");
    }

    @Test
    void surfacesAResolutionFailureAsAProblemDetail() throws Exception {
        when(preview.preview("ghost.yaml", "local:v2.yaml"))
                .thenThrow(ContractGuardException.of(FailureCategory.INVALID_OPENAPI,
                        "specification file not found: ghost.yaml",
                        "Choose a file from the specification listing."));

        mvc.perform(get("/api/spec-diff").param("oldSpec", "ghost.yaml").param("newSpec", "local:v2.yaml"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("INVALID_OPENAPI"));
    }
}
