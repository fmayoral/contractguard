package com.contractguard.adapter.web;

import com.contractguard.adapter.web.dto.AuditDtos;
import com.contractguard.application.service.AuditTrailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only audit trail surface (FR-025): every state transition, approval
 * decision and repository mutation, principal-attributed. No mutation
 * endpoint exists here by design — the trail is append-only end to end.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditTrailService audit;

    public AuditController(AuditTrailService audit) {
        this.audit = audit;
    }

    @GetMapping
    public List<AuditDtos.Entry> list(
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String repositoryId) {
        if (runId != null) {
            return AuditDtos.from(audit.findByRun(runId));
        }
        if (repositoryId != null) {
            return AuditDtos.from(audit.findByRepository(repositoryId));
        }
        return AuditDtos.from(audit.findAll());
    }
}
