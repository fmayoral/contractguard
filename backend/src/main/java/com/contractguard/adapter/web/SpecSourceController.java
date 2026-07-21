package com.contractguard.adapter.web;

import com.contractguard.adapter.web.dto.DtoMapper;
import com.contractguard.adapter.web.dto.RunDtos;
import com.contractguard.application.service.SpecSourceService;
import com.contractguard.domain.RemoteRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Spec-source repository registration (FR-043, ADR-0012). Thin by design, like {@link RemoteRepositoryController}. */
@RestController
@RequestMapping("/api/spec-sources")
public class SpecSourceController {

    private final SpecSourceService service;

    public SpecSourceController(SpecSourceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RunDtos.SpecSourceSummary> register(
            @Valid @RequestBody RunDtos.RegisterSpecSourceRequest request) {
        RemoteRepository registered = service.register(request.repositoryId(), request.cloneUrl(),
                request.defaultBranch(), request.token());
        return ResponseEntity.status(HttpStatus.CREATED).body(DtoMapper.toSpecSourceSummary(registered));
    }
}
