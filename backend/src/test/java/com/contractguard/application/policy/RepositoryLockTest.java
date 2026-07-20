package com.contractguard.application.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryLockTest {

    @Test
    void secondAcquireForTheSameRepositoryFailsUntilReleased() {
        RepositoryLock lock = new RepositoryLock();

        assertThat(lock.tryAcquire("repo-1")).isTrue();
        assertThat(lock.tryAcquire("repo-1")).isFalse();

        lock.release("repo-1");

        assertThat(lock.tryAcquire("repo-1")).isTrue();
    }

    @Test
    void differentRepositoriesDoNotContendForTheSameLock() {
        RepositoryLock lock = new RepositoryLock();

        assertThat(lock.tryAcquire("repo-1")).isTrue();
        assertThat(lock.tryAcquire("repo-2")).isTrue();
    }

    @Test
    void releasingAnUnheldRepositoryIsANoOp() {
        RepositoryLock lock = new RepositoryLock();

        lock.release("never-held");

        assertThat(lock.tryAcquire("never-held")).isTrue();
    }
}
