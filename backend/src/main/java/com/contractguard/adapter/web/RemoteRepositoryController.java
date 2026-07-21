package com.contractguard.adapter.web;

import com.contractguard.adapter.web.dto.RunDtos;
import com.contractguard.application.service.RemoteRepositoryService;
import com.contractguard.domain.RemoteRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Remote repository registration (FR-027) and deregistration (FR-044). Thin by design, like {@link RunController}. */
@RestController
@RequestMapping("/api/repositories")
public class RemoteRepositoryController {

    private final RemoteRepositoryService service;

    public RemoteRepositoryController(RemoteRepositoryService service) {
        this.service = service;
    }

    @PostMapping("/remote")
    public ResponseEntity<RunDtos.RemoteRepositorySummary> register(
            @Valid @RequestBody RunDtos.RegisterRemoteRepositoryRequest request) {
        RemoteRepository registered = service.register(request.repositoryId(), request.cloneUrl(),
                request.defaultBranch(), request.token());
        RunDtos.RemoteRepositorySummary body = new RunDtos.RemoteRepositorySummary(
                registered.repositoryId(), registered.owner(), registered.name(),
                registered.defaultBranch(), registered.registeredAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/remote/{repositoryId}")
    public ResponseEntity<Void> deregister(@PathVariable String repositoryId) {
        service.deregister(repositoryId);
        return ResponseEntity.noContent().build();
    }
}
