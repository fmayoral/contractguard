package com.contractguard.adapter.web;

import com.contractguard.application.service.RemoteRepositoryService;
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

@WebMvcTest(RemoteRepositoryController.class)
class RemoteRepositoryControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RemoteRepositoryService service;

    @Test
    void registersARemoteRepositoryAndReturns201() throws Exception {
        RemoteRepository remote = RemoteRepository.forGitHub("customer-consumer",
                "https://github.com/acme/widgets", "main", Instant.parse("2026-07-20T10:00:00Z"));
        when(service.register("customer-consumer", "https://github.com/acme/widgets", "main", "gh-token"))
                .thenReturn(remote);

        mvc.perform(post("/api/repositories/remote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repositoryId":"customer-consumer",
                                 "cloneUrl":"https://github.com/acme/widgets",
                                 "defaultBranch":"main","token":"gh-token"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.repositoryId").value("customer-consumer"))
                .andExpect(jsonPath("$.owner").value("acme"))
                .andExpect(jsonPath("$.name").value("widgets"));
    }

    @Test
    void invalidCloneUrlGets400ProblemDetail() throws Exception {
        when(service.register(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(ContractGuardException.of(FailureCategory.INVALID_REMOTE_URL,
                        "clone URL is not a supported GitHub HTTPS URL", "Use an https://github.com URL."));

        mvc.perform(post("/api/repositories/remote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"repositoryId":"customer-consumer",
                                 "cloneUrl":"git@github.com:acme/widgets.git",
                                 "defaultBranch":"main","token":"gh-token"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.category").value("INVALID_REMOTE_URL"));
    }

    @Test
    void blankFieldsFailValidation() throws Exception {
        mvc.perform(post("/api/repositories/remote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("INVALID_REQUEST"));
    }

    @Test
    void deregistersARepositoryAndReturns204() throws Exception {
        mvc.perform(delete("/api/repositories/remote/customer-consumer"))
                .andExpect(status().isNoContent());

        verify(service).deregister("customer-consumer");
    }
}
