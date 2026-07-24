package com.contractguard.application.service;

import com.contractguard.application.port.OpenApiDiffPort;
import com.contractguard.domain.ApiChange;

import java.util.List;

/**
 * Deterministic, side-effect-free preview of what a run would detect between two
 * specifications: no run is created, no repository is touched, no LLM is called. Reuses the
 * exact same resolution ({@link SpecResolutionService}) and diff/classification
 * ({@link OpenApiDiffPort}) logic {@code AnalysisPipeline}'s own diff step uses, so the preview
 * and a real run's first step can never disagree.
 */
public class SpecPreviewService {

    /** Deliberately not {@link OpenApiDiffPort.DiffResult} itself -- adapters may depend on
     * application services, never on application ports (hexagonal rule), so this service
     * exposes its own plain result instead of leaking the port's own DTO. */
    public record Preview(List<ApiChange> changes, List<String> warnings) {
    }

    private final SpecResolutionService specs;
    private final OpenApiDiffPort diffPort;

    public SpecPreviewService(SpecResolutionService specs, OpenApiDiffPort diffPort) {
        this.specs = specs;
        this.diffPort = diffPort;
    }

    public Preview preview(String oldSpecId, String newSpecId) {
        OpenApiDiffPort.DiffResult result = diffPort.diff(specs.resolve(oldSpecId), specs.resolve(newSpecId));
        return new Preview(result.changes(), result.warnings());
    }
}
