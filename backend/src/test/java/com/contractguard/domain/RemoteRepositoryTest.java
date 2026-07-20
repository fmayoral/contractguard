package com.contractguard.domain;

import org.junit.jupiter.api.Test;

import static com.contractguard.domain.Fixtures.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteRepositoryTest {

    @Test
    void parsesOwnerAndNameFromAGitHubHttpsUrl() {
        RemoteRepository remote = RemoteRepository.forGitHub(
                "customer-consumer", "https://github.com/acme/widgets", "main", T0);

        assertThat(remote.owner()).isEqualTo("acme");
        assertThat(remote.name()).isEqualTo("widgets");
        assertThat(remote.defaultBranch()).isEqualTo("main");
    }

    @Test
    void tolerantOfATrailingDotGitAndSlash() {
        RemoteRepository remote = RemoteRepository.forGitHub(
                "customer-consumer", "https://github.com/acme/widgets.git/", "main", T0);

        assertThat(remote.owner()).isEqualTo("acme");
        assertThat(remote.name()).isEqualTo("widgets");
    }

    @Test
    void rejectsNonGitHubUrls() {
        assertThatThrownBy(() -> RemoteRepository.forGitHub(
                "customer-consumer", "https://gitlab.com/acme/widgets", "main", T0))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.INVALID_REMOTE_URL));
    }

    @Test
    void rejectsNonHttpsUrls() {
        assertThatThrownBy(() -> RemoteRepository.forGitHub(
                "customer-consumer", "git@github.com:acme/widgets.git", "main", T0))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.INVALID_REMOTE_URL));
    }
}
