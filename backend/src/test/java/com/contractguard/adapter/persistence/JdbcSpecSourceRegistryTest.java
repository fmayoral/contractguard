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

class JdbcSpecSourceRegistryTest {

    private static final Instant REGISTERED_AT = Instant.parse("2026-07-21T10:00:00Z");

    private JdbcSpecSourceRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        dataSource.setUser("sa");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/h2/V4__spec_source_repositories.sql"));
        }
        AesGcmCredentialCipher cipher =
                new AesGcmCredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
        registry = new JdbcSpecSourceRegistry(new JdbcTemplate(dataSource), cipher);
    }

    @Test
    void registeredSourceAndCredentialSurviveARoundTrip() {
        RemoteRepository source = RemoteRepository.forGitHub(
                "openapi-specs", "https://github.com/acme/openapi-specs", "main", REGISTERED_AT);

        registry.register(source, "gh-token-abc123");

        Optional<RemoteRepository> found = registry.find("openapi-specs");
        assertThat(found).isPresent();
        assertThat(found.orElseThrow()).usingRecursiveComparison().isEqualTo(source);
        assertThat(registry.credentialFor("openapi-specs")).contains("gh-token-abc123");
    }

    @Test
    void aBlankTokenIsStoredAsNoCredentialAtAllNotAnEncryptedEmptyString() {
        RemoteRepository source = RemoteRepository.forGitHub(
                "public-openapi-specs", "https://github.com/acme/public-openapi-specs", "main", REGISTERED_AT);

        registry.register(source, "");

        // Never calls the cipher for a blank token, so a repository this public never needs
        // CONTRACTGUARD_CREDENTIAL_KEY configured at all (ADR-0012 decision #2).
        assertThat(registry.credentialFor("public-openapi-specs")).isEmpty();
        assertThat(registry.find("public-openapi-specs")).isPresent();
    }

    @Test
    void unknownSourceIsAbsent() {
        assertThat(registry.find("unknown")).isEmpty();
        assertThat(registry.credentialFor("unknown")).isEmpty();
    }

    @Test
    void reRegisteringUpdatesInsteadOfDuplicating() {
        RemoteRepository first = RemoteRepository.forGitHub(
                "openapi-specs", "https://github.com/acme/openapi-specs", "main", REGISTERED_AT);
        registry.register(first, "gh-token-old");

        RemoteRepository updated = RemoteRepository.forGitHub(
                "openapi-specs", "https://github.com/acme/openapi-specs", "develop", REGISTERED_AT);
        registry.register(updated, "gh-token-new");

        assertThat(registry.find("openapi-specs").orElseThrow().defaultBranch()).isEqualTo("develop");
        assertThat(registry.credentialFor("openapi-specs")).contains("gh-token-new");
    }

    @Test
    void reRegisteringWithABlankTokenClearsThePreviousCredential() {
        RemoteRepository source = RemoteRepository.forGitHub(
                "openapi-specs", "https://github.com/acme/openapi-specs", "main", REGISTERED_AT);
        registry.register(source, "gh-token-old");

        registry.register(source, "");

        assertThat(registry.credentialFor("openapi-specs")).isEmpty();
    }

    @Test
    void findAllListsEveryRegistrationOrderedById() {
        registry.register(RemoteRepository.forGitHub(
                "widgets-specs", "https://github.com/acme/widgets-specs", "main", REGISTERED_AT), "t1");
        registry.register(RemoteRepository.forGitHub(
                "gadgets-specs", "https://github.com/acme/gadgets-specs", "main", REGISTERED_AT), "t2");

        assertThat(registry.findAll()).extracting("repositoryId").containsExactly("gadgets-specs", "widgets-specs");
    }
}
