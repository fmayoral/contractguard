package com.contractguard.application.policy;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process per-repository mutual exclusion (FR-032). Closes the race in
 * {@code RunService.createRun} where two simultaneous requests for the same
 * repository could both pass the "any active run?" check before either row
 * is written — the lock only needs to guard that tiny check-then-insert
 * window, not the run's full lifetime, since once the row exists the
 * existing {@code findActiveByRepository} query correctly represents "busy"
 * for as long as the run stays non-terminal (see ADR-0009).
 *
 * <p>An in-process lock is sufficient because ContractGuard is a single-
 * instance, local-first deployment (§ out of scope: no multi-instance
 * story); a distributed lock would be needed only if that changed.
 */
public final class RepositoryLock {

    private final ConcurrentHashMap<String, Boolean> held = new ConcurrentHashMap<>();

    /** @return true if the caller now holds the lock; false if another caller already does */
    public boolean tryAcquire(String repositoryId) {
        return held.putIfAbsent(repositoryId, Boolean.TRUE) == null;
    }

    public void release(String repositoryId) {
        held.remove(repositoryId);
    }
}
