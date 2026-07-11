package com.contractguard.application.service;

import com.contractguard.application.port.RunRepository;
import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;

/** Shared load-or-fail access to the run aggregate for the application services. */
final class RunLookup {

    private RunLookup() {
    }

    static AnalysisRun require(RunRepository runs, String runId) {
        return runs.findById(runId).orElseThrow(() -> ContractGuardException.of(
                FailureCategory.NOT_FOUND, "run not found: " + runId, "Check the run ID."));
    }
}
