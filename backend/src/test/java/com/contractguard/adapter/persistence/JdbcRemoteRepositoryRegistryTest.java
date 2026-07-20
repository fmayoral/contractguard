package com.contractguard.adapter.persistence;

import com.contractguard.adapter.security.AesGcmCredentialCipher;
import com.contractguard.domain.RemoteRepository;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRemoteRepositoryRegistryTest {

    private static final Instant REGISTERED_AT = Instant.parse("2026-07-20T10:00:00Z");

    private JdbcRemoteRepositoryRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        dataSource.setUser("sa");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/h2/V2__remote_repositories.sql"));
        }
        AesGcmCredentialCipher cipher =
                new AesGcmCredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
        registry = new JdbcRemoteRepositoryRegistry(new JdbcTemplate(dataSource), cipher);
    }

    @Test
    void registeredRepositoryAndCredentialSurviveARoundTrip() {
        RemoteRepository remote = RemoteRepository.forGitHub(
                "customer-consumer", "https://github.com/acme/widgets", "main", REGISTERED_AT);

        registry.register(remote, "gh-token-abc123");

        Optional<RemoteRepository> found = registry.find("customer-consumer");
        assertThat(found).isPresent();
        assertThat(found.orElseThrow()).usingRecursiveComparison().isEqualTo(remote);
        assertThat(registry.credentialFor("customer-consumer")).contains("gh-token-abc123");
    }

    @Test
    void unknownRepositoryIsAbsent() {
        assertThat(registry.find("unknown")).isEmpty();
        assertThat(registry.credentialFor("unknown")).isEmpty();
    }

    @Test
    void reRegisteringUpdatesInsteadOfDuplicating() {
        RemoteRepository first = RemoteRepository.forGitHub(
                "customer-consumer", "https://github.com/acme/widgets", "main", REGISTERED_AT);
        registry.register(first, "gh-token-old");

        RemoteRepository updated = RemoteRepository.forGitHub(
                "customer-consumer", "https://github.com/acme/widgets", "develop", REGISTERED_AT);
        registry.register(updated, "gh-token-new");

        assertThat(registry.find("customer-consumer").orElseThrow().defaultBranch()).isEqualTo("develop");
        assertThat(registry.credentialFor("customer-consumer")).contains("gh-token-new");
    }

    @Test
    void findAllListsEveryRegistrationOrderedById() {
        registry.register(RemoteRepository.forGitHub(
                "widgets", "https://github.com/acme/widgets", "main", REGISTERED_AT), "t1");
        registry.register(RemoteRepository.forGitHub(
                "gadgets", "https://github.com/acme/gadgets", "main", REGISTERED_AT), "t2");

        assertThat(registry.findAll()).extracting("repositoryId").containsExactly("gadgets", "widgets");
    }
}
