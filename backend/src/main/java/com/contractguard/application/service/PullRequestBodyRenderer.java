package com.contractguard.application.service;

import com.contractguard.domain.AnalysisRun;
import com.contractguard.domain.ApiChange;
import com.contractguard.domain.ImpactEvidence;
import com.contractguard.domain.MigrationPlan;
import com.contractguard.domain.RemoteRepository;

/**
 * Renders the draft pull request body: a summary of what changed and why,
 * with evidence linked back into the target repository's own file tree
 * rather than to ContractGuard (which has no public URL by default,
 * ADR-0007) — the GitHub blob link is more useful to the reviewer anyway.
 */
final class PullRequestBodyRenderer {

    private PullRequestBodyRenderer() {
    }

    static String render(AnalysisRun run, RemoteRepository remote) {
        StringBuilder body = new StringBuilder();
        body.append("Opened by ContractGuard for run `").append(run.id()).append("`.\n\n");

        body.append("## Detected changes\n\n");
        for (ApiChange change : run.changes()) {
            body.append("- **").append(change.classification()).append("** ");
            if (change.method() != null) {
                body.append(change.method()).append(' ');
            }
            if (change.path() != null) {
                body.append(change.path()).append(' ');
            }
            body.append("— ").append(change.reason()).append('\n');
        }

        body.append("\n## Evidence\n\n");
        for (ImpactEvidence item : run.evidence()) {
            body.append("- [`").append(item.relativePath()).append(':').append(item.startLine())
                    .append("`](https://github.com/").append(remote.owner()).append('/')
                    .append(remote.name()).append("/blob/").append(run.workingBranch()).append('/')
                    .append(item.relativePath()).append("#L").append(item.startLine())
                    .append(") — ").append(item.relationship()).append('\n');
        }

        String planHash = run.plan().map(MigrationPlan::hash).orElse("n/a");
        body.append("\nEvery patch in this pull request was generated against the approved migration plan ")
                .append("(hash `").append(planHash).append("`) and validated before being applied. ")
                .append("See the full ContractGuard report for the migration plan and validation log.\n");
        return body.toString();
    }
}
