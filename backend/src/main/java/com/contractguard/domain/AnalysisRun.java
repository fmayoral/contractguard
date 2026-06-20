package com.contractguard.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate root of one ContractGuard run. All state changes go through
 * guarded methods: the state machine ({@link RunState}) rejects illegal
 * transitions, approval is verified against the exact plan hash, and the
 * one-repair-attempt limit is structural (patch/validation attempt numbers).
 */
public final class AnalysisRun {

    /** Maximum validation attempts: the initial one plus a single repair (FR-017). */
    public static final int MAX_VALIDATION_ATTEMPTS = 2;

    private final String id;
    private final String name;
    private final String repositoryId;
    private final String traceId;
    private final Instant createdAt;
    private Instant updatedAt;
    private RunState state;
    private String oldSpecName;
    private String newSpecName;
    private String oldSpecHash;
    private String newSpecHash;
    private String originalBranch;
    private String workingBranch;
    private RunFailure failure;

    private final List<ApiChange> changes = new ArrayList<>();
    private final List<ImpactEvidence> evidence = new ArrayList<>();
    private final List<ImpactAssessment> assessments = new ArrayList<>();
    private final List<PatchArtifact> patches = new ArrayList<>();
    private final List<ValidationResult> validations = new ArrayList<>();
    private MigrationPlan plan;
    private Approval approval;

    public AnalysisRun(String id, String name, String repositoryId, String traceId, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.repositoryId = Objects.requireNonNull(repositoryId, "repositoryId");
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = createdAt;
        this.state = RunState.CREATED;
    }

    /** Rehydration constructor for persistence; performs no transition checks. */
    public static AnalysisRun rehydrate(String id, String name, String repositoryId, String traceId,
            Instant createdAt, Instant updatedAt, RunState state, String oldSpecName, String newSpecName,
            String oldSpecHash, String newSpecHash,
            String originalBranch, String workingBranch, RunFailure failure, List<ApiChange> changes,
            List<ImpactEvidence> evidence, List<ImpactAssessment> assessments, MigrationPlan plan,
            Approval approval, List<PatchArtifact> patches, List<ValidationResult> validations) {
        AnalysisRun run = new AnalysisRun(id, name, repositoryId, traceId, createdAt);
        run.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        run.state = Objects.requireNonNull(state, "state");
        run.oldSpecName = oldSpecName;
        run.newSpecName = newSpecName;
        run.oldSpecHash = oldSpecHash;
        run.newSpecHash = newSpecHash;
        run.originalBranch = originalBranch;
        run.workingBranch = workingBranch;
        run.failure = failure;
        run.changes.addAll(changes);
        run.evidence.addAll(evidence);
        run.assessments.addAll(assessments);
        run.plan = plan;
        run.approval = approval;
        run.patches.addAll(patches);
        run.validations.addAll(validations);
        return run;
    }

    public void transitionTo(RunState target, Instant now) {
        if (!state.canTransitionTo(target)) {
            throw ContractGuardException.of(FailureCategory.ILLEGAL_STATE,
                    "illegal transition %s -> %s for run %s".formatted(state, target, id),
                    "No action required; the requested operation is not valid in the current state.");
        }
        state = target;
        touch(now);
    }

    public void recordSpecs(String oldName, String newName, String oldHash, String newHash, Instant now) {
        this.oldSpecName = Objects.requireNonNull(oldName, "oldName");
        this.newSpecName = Objects.requireNonNull(newName, "newName");
        this.oldSpecHash = Objects.requireNonNull(oldHash, "oldHash");
        this.newSpecHash = Objects.requireNonNull(newHash, "newHash");
        touch(now);
    }

    public void recordChanges(List<ApiChange> detected, Instant now) {
        requireState(RunState.DIFFING, "record changes");
        changes.clear();
        changes.addAll(detected);
        touch(now);
    }

    /** Replaces a change with an explained copy; facts are immutable, only explanation may differ. */
    public void attachExplanation(String changeId, String explanation, Instant now) {
        for (int i = 0; i < changes.size(); i++) {
            if (changes.get(i).id().equals(changeId)) {
                changes.set(i, changes.get(i).withExplanation(explanation));
                touch(now);
                return;
            }
        }
        throw new IllegalArgumentException("unknown change " + changeId);
    }

    public void recordEvidence(List<ImpactEvidence> found, Instant now) {
        requireState(RunState.SEARCHING, "record evidence");
        evidence.clear();
        evidence.addAll(found);
        touch(now);
    }

    /** Assessments may only cite evidence that deterministically exists on this run (FR-008). */
    public void recordAssessments(List<ImpactAssessment> produced, Instant now) {
        requireState(RunState.ASSESSING, "record assessments");
        for (ImpactAssessment assessment : produced) {
            for (String evidenceId : assessment.evidenceIds()) {
                if (evidence.stream().noneMatch(e -> e.id().equals(evidenceId))) {
                    throw ContractGuardException.of(FailureCategory.POLICY_VIOLATION,
                            "assessment %s cites unknown evidence %s".formatted(assessment.id(), evidenceId),
                            "Reject the agent output; assessments must cite collected evidence only.");
                }
            }
        }
        assessments.clear();
        assessments.addAll(produced);
        touch(now);
    }

    /** Attaching a (new) plan clears any previous approval: plan change invalidates approval. */
    public void attachPlan(MigrationPlan newPlan, Instant now) {
        requireState(RunState.PLANNING, "attach plan");
        this.plan = Objects.requireNonNull(newPlan, "plan");
        this.approval = null;
        touch(now);
    }

    public void recordApproval(Approval decision, Instant now) {
        requireState(RunState.AWAITING_APPROVAL, "record approval");
        if (plan == null) {
            throw ContractGuardException.of(FailureCategory.ILLEGAL_STATE,
                    "run %s has no plan to approve".formatted(id), "Re-run the analysis to produce a plan.");
        }
        if (!plan.hash().equals(decision.planHash())) {
            throw ContractGuardException.of(FailureCategory.APPROVAL_MISMATCH,
                    "approval hash %s does not match current plan hash %s".formatted(decision.planHash(), plan.hash()),
                    "Reload the plan and decide again against its current version.");
        }
        this.approval = decision;
        touch(now);
    }

    public boolean isApproved() {
        return approval != null && approval.decision() == Approval.Decision.APPROVED
                && plan != null && plan.hash().equals(approval.planHash());
    }

    public void recordBranches(String original, String working, Instant now) {
        requireState(RunState.PREPARING_BRANCH, "record branches");
        this.originalBranch = Objects.requireNonNull(original, "original");
        this.workingBranch = Objects.requireNonNull(working, "working");
        touch(now);
    }

    public void recordPatch(PatchArtifact patch, Instant now) {
        if (state != RunState.PATCHING && state != RunState.REPAIRING) {
            throw illegalIn("record patch");
        }
        int expectedAttempt = patches.size() + 1;
        if (patch.attempt() != expectedAttempt) {
            throw ContractGuardException.of(FailureCategory.ILLEGAL_STATE,
                    "patch attempt %d out of order, expected %d".formatted(patch.attempt(), expectedAttempt),
                    "This indicates a workflow bug; inspect the run trace.");
        }
        patches.add(patch);
        touch(now);
    }

    public void markPatchApplied(String patchId, Instant now) {
        for (int i = 0; i < patches.size(); i++) {
            if (patches.get(i).id().equals(patchId)) {
                patches.set(i, patches.get(i).asApplied(now));
                touch(now);
                return;
            }
        }
        throw new IllegalArgumentException("unknown patch " + patchId);
    }

    public void recordValidation(ValidationResult result, Instant now) {
        requireState(RunState.VALIDATING, "record validation");
        if (validations.size() >= MAX_VALIDATION_ATTEMPTS) {
            throw ContractGuardException.of(FailureCategory.POLICY_VIOLATION,
                    "run %s exceeded the maximum of %d validation attempts".formatted(id, MAX_VALIDATION_ATTEMPTS),
                    "The bounded repair limit was reached; the run must finish as FAILED.");
        }
        if (result.attempt() != validations.size() + 1) {
            throw ContractGuardException.of(FailureCategory.ILLEGAL_STATE,
                    "validation attempt %d out of order".formatted(result.attempt()),
                    "This indicates a workflow bug; inspect the run trace.");
        }
        validations.add(result);
        touch(now);
    }

    /** A repair is allowed exactly once, and only after a failed first validation (FR-017). */
    public boolean repairAllowed() {
        return validations.size() == 1 && !validations.get(0).successful();
    }

    public void markFailed(RunFailure runFailure, Instant now) {
        if (state.isTerminal()) {
            throw illegalIn("mark failed");
        }
        this.failure = Objects.requireNonNull(runFailure, "failure");
        this.state = RunState.FAILED;
        touch(now);
    }

    public void markCancelled(Instant now) {
        transitionTo(RunState.CANCELLED, now);
    }

    private void requireState(RunState expected, String action) {
        if (state != expected) {
            throw illegalIn(action);
        }
    }

    private ContractGuardException illegalIn(String action) {
        return ContractGuardException.of(FailureCategory.ILLEGAL_STATE,
                "cannot %s in state %s for run %s".formatted(action, state, id),
                "No action required; the requested operation is not valid in the current state.");
    }

    private void touch(Instant now) {
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String repositoryId() {
        return repositoryId;
    }

    public String traceId() {
        return traceId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public RunState state() {
        return state;
    }

    public String oldSpecName() {
        return oldSpecName;
    }

    public String newSpecName() {
        return newSpecName;
    }

    public String oldSpecHash() {
        return oldSpecHash;
    }

    public String newSpecHash() {
        return newSpecHash;
    }

    public String originalBranch() {
        return originalBranch;
    }

    public String workingBranch() {
        return workingBranch;
    }

    public Optional<RunFailure> failure() {
        return Optional.ofNullable(failure);
    }

    public List<ApiChange> changes() {
        return List.copyOf(changes);
    }

    public List<ImpactEvidence> evidence() {
        return List.copyOf(evidence);
    }

    public List<ImpactAssessment> assessments() {
        return List.copyOf(assessments);
    }

    public Optional<MigrationPlan> plan() {
        return Optional.ofNullable(plan);
    }

    public Optional<Approval> approval() {
        return Optional.ofNullable(approval);
    }

    public List<PatchArtifact> patches() {
        return List.copyOf(patches);
    }

    public List<ValidationResult> validations() {
        return List.copyOf(validations);
    }
}
