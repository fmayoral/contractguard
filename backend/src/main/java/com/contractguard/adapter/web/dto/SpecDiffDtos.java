package com.contractguard.adapter.web.dto;

import com.contractguard.application.service.SpecPreviewService;

import java.util.List;

/** Wire shape for the deterministic spec-diff preview (no run, no LLM). */
public final class SpecDiffDtos {

    private SpecDiffDtos() {
    }

    public record Preview(List<RunDtos.Change> changes, List<String> warnings) {

        public static Preview from(SpecPreviewService.Preview result) {
            return new Preview(DtoMapper.toChanges(result.changes()), result.warnings());
        }
    }
}
