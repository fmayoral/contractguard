package com.contractguard.adapter.web;

import com.contractguard.adapter.web.dto.SpecDiffDtos;
import com.contractguard.application.service.SpecPreviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only, side-effect-free preview of what a run would detect between two specifications:
 * no run is created, no repository is touched, no LLM is called. Lets New Run show whether two
 * specs even differ before committing to a full analysis.
 */
@RestController
public class SpecDiffController {

    private final SpecPreviewService preview;

    public SpecDiffController(SpecPreviewService preview) {
        this.preview = preview;
    }

    @GetMapping("/api/spec-diff")
    public SpecDiffDtos.Preview preview(@RequestParam String oldSpec, @RequestParam String newSpec) {
        return SpecDiffDtos.Preview.from(preview.preview(oldSpec, newSpec));
    }
}
