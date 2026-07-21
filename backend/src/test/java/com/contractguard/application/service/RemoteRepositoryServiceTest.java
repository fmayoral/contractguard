package com.contractguard.application.service;

import com.contractguard.application.port.RemoteGitPort;
import com.contractguard.application.port.RemoteRepositoryRegistry;
import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.RemoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteRepositoryServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-20T10:00:00Z"), ZoneOffset.UTC);

    private final Map<String, RemoteRepository> registered = new HashMap<>();
    private final Map<String, String> tokens = new HashMap<>();
    private final Map<String, Integer> cloneCalls = new HashMap<>();

    private RemoteRepositoryRegistry registry;
    private RemoteGitPort remoteGit;
    private RemoteRepositoryService service;

    @BeforeEach
    void setUp() {
        registry = new RemoteRepositoryRegistry() {
            @Override
            public void register(RemoteRepository repository, String token) {
                registered.put(repository.repositoryId(), repository);
                tokens.put(repository.repositoryId(), token);
            }

            @Override
            public Optional<RemoteRepository> find(String repositoryId) {
                return Optional.ofNullable(registered.get(repositoryId));
            }

            @Override
            public Optional<String> credentialFor(String repositoryId) {
                return Optional.ofNullable(tokens.get(repositoryId));
            }

            @Override
            public List<RemoteRepository> findAll() {
                return List.copyOf(registered.values());
            }

            @Override
            public void deregister(String repositoryId) {
                registered.remove(repositoryId);
                tokens.remove(repositoryId);
            }
        };
        remoteGit = new RemoteGitPort() {
            @Override
            public void cloneOrRefresh(String repositoryId, RemoteRepository remote, String credential) {
                cloneCalls.merge(repositoryId, 1, Integer::sum);
            }

            @Override
            public void push(String repositoryId, String branchName, RemoteRepository remote, String credential) {
                throw new UnsupportedOperationException("push not used by RemoteRepositoryService");
            }

            @Override
            public void deleteLocalClone(String repositoryId) {
                cloneCalls.remove(repositoryId);
            }
        };
        service = new RemoteRepositoryService(registry, remoteGit, CLOCK);
    }

    @Test
    void registersAValidGitHubRepository() {
        RemoteRepository remote = service.register("customer-consumer",
                "https://github.com/acme/widgets", "main", "gh-token");

        assertThat(remote.owner()).isEqualTo("acme");
        assertThat(remote.name()).isEqualTo("widgets");
        assertThat(registered).containsKey("customer-consumer");
        assertThat(tokens.get("customer-consumer")).isEqualTo("gh-token");
    }

    @Test
    void rejectsRepositoryIdsWithPathSeparators() {
        assertThatThrownBy(() -> service.register("../escape",
                "https://github.com/acme/widgets", "main", "gh-token"))
                .isInstanceOf(ContractGuardException.class)
                .satisfies(e -> assertThat(((ContractGuardException) e).failure().category())
                        .isEqualTo(FailureCategory.POLICY_VIOLATION));
    }

    @Test
    void deregisterRemovesTheRegistrationAndTheLocalCloneCache() {
        service.register("customer-consumer", "https://github.com/acme/widgets", "main", "gh-token");
        service.ensureLocalClone("customer-consumer");
        assertThat(cloneCalls).containsKey("customer-consumer");

        service.deregister("customer-consumer");

        assertThat(registered).doesNotContainKey("customer-consumer");
        assertThat(cloneCalls).doesNotContainKey("customer-consumer");
        assertThat(service.isRemote("customer-consumer")).isFalse();
    }

    @Test
    void deregisterIsANoOpForARepositoryThatWasNeverRegistered() {
        service.deregister("never-registered");

        assertThat(registered).isEmpty();
    }

    @Test
    void ensureLocalCloneIsNoOpForUnregisteredRepositories() {
        service.ensureLocalClone("local-only-repo");

        assertThat(cloneCalls).isEmpty();
    }

    @Test
    void ensureLocalCloneClonesRegisteredRepositories() {
        service.register("customer-consumer", "https://github.com/acme/widgets", "main", "gh-token");

        service.ensureLocalClone("customer-consumer");

        assertThat(cloneCalls).containsEntry("customer-consumer", 1);
    }

    @Test
    void listRegisteredReturnsEveryRegistration() {
        assertThat(service.listRegistered()).isEmpty();

        service.register("customer-consumer", "https://github.com/acme/widgets", "main", "gh-token");
        service.register("other-consumer", "https://github.com/acme/gadgets", "main", "gh-token-2");

        assertThat(service.listRegistered()).extracting("repositoryId")
                .containsExactlyInAnyOrder("customer-consumer", "other-consumer");
    }

    @Test
    void isRemoteReflectsRegistrationState() {
        assertThat(service.isRemote("customer-consumer")).isFalse();
        service.register("customer-consumer", "https://github.com/acme/widgets", "main", "gh-token");
        assertThat(service.isRemote("customer-consumer")).isTrue();
    }
}
