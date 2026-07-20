package com.contractguard.application.port;

/** Opens draft pull requests on the hosting provider (GitHub only, FR-027). */
public interface PullRequestPort {

    PullRequestResult openDraftPullRequest(PullRequestRequest request);

    record PullRequestRequest(String owner, String repo, String headBranch, String baseBranch,
            String title, String body, String credential) {
    }

    record PullRequestResult(String url, int number) {
    }
}
