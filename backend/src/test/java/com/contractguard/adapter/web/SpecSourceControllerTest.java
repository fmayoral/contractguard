package com.contractguard.adapter.web;

import com.contractguard.application.service.SpecSourceService;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.RemoteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SpecSourceController.class)
class SpecSourceControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SpecSourceService service;

    @Test
    void registersASpecSourceWithATokenAndReturns201() throws Exception {
        RemoteRepository source = RemoteRepository.forGitHub("openapi-specs",
                "https://github.com/acme/openapi-specs", "main", Instant.parse("2026-07-21T10:00:00Z"));
        when(service.register("openapi-specs", "https://github.com/acme/openapi-specs", "main", "gh-token"))
                .thenReturn(source);

        mvc.perform(post("/api/spec-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repositoryId":"openapi-specs",
                                 "cloneUrl":"https://github.com/acme/openapi-specs",
                                 "defaultBranch":"main","token":"gh-token"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repositoryId").value("openapi-specs"))
                .andExpect(jsonPath("$.owner").value("acme"))
                .andExpect(jsonPath("$.name").value("openapi-specs"));
    }

    @Test
    void aMissingTokenIsAcceptedForAPublicSpecSource() throws Exception {
        RemoteRepository source = RemoteRepository.forGitHub("public-specs",
                "https://github.com/acme/public-specs", "main", Instant.parse("2026-07-21T10:00:00Z"));
        when(service.register("public-specs", "https://github.com/acme/public-specs", "main", null))
                .thenReturn(source);

        mvc.perform(post("/api/spec-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repositoryId":"public-specs",
                                 "cloneUrl":"https://github.com/acme/public-specs",
                                 "defaultBranch":"main"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repositoryId").value("public-specs"));
    }

    @Test
    void invalidCloneUrlGets400ProblemDetail() throws Exception {
        when(service.register(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(ContractGuardException.of(FailureCategory.INVALID_REMOTE_URL,
                        "clone URL is not a supported GitHub HTTPS URL", "Use an https://github.com URL."));

        mvc.perform(post("/api/spec-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repositoryId":"openapi-specs",
                                 "cloneUrl":"git@github.com:acme/openapi-specs.git",
                                 "defaultBranch":"main","token":"gh-token"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("INVALID_REMOTE_URL"));
    }

    @Test
    void blankRepositoryIdFailsValidation() throws Exception {
        mvc.perform(post("/api/spec-sources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("INVALID_REQUEST"));
    }

    @Test
    void deregistersASourceAndReturns204() throws Exception {
        mvc.perform(delete("/api/spec-sources/openapi-specs"))
                .andExpect(status().isNoContent());

        verify(service).deregister("openapi-specs");
    }
}
