package com.contractguard.adapter.persistence;

import com.contractguard.adapter.security.AesGcmCredentialCipher;
import com.contractguard.adapter.security.AesGcmCredentialCipher.EncryptedValue;
import com.contractguard.application.port.SpecSourceRegistry;
import com.contractguard.domain.RemoteRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * JDBC-backed spec-source registry (FR-043, ADR-0012). Unlike
 * {@link JdbcRemoteRepositoryRegistry}, the credential columns are
 * nullable: a blank token at registration is stored as no credential at
 * all, so a public spec repository never requires
 * {@code CONTRACTGUARD_CREDENTIAL_KEY} to be configured.
 */
public class JdbcSpecSourceRegistry implements SpecSourceRegistry {

    private final JdbcTemplate jdbc;
    private final AesGcmCredentialCipher cipher;

    private final RowMapper<RemoteRepository> rowMapper = (rs, rowNum) -> new RemoteRepository(
            rs.getString("repository_id"), rs.getString("clone_url"), rs.getString("owner"),
            rs.getString("name"), rs.getString("default_branch"), rs.getTimestamp("registered_at").toInstant());

    public JdbcSpecSourceRegistry(JdbcTemplate jdbc, AesGcmCredentialCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public void register(RemoteRepository repository, String token) {
        EncryptedValue encrypted = token == null || token.isBlank() ? null : cipher.encrypt(token);
        String ciphertext = encrypted == null ? null : encrypted.ciphertextBase64();
        String nonce = encrypted == null ? null : encrypted.nonceBase64();
        int updated = jdbc.update("""
                UPDATE spec_source_repositories SET clone_url = ?, owner = ?, name = ?, default_branch = ?,
                    credential_ciphertext = ?, credential_nonce = ?, registered_at = ?
                WHERE repository_id = ?""",
                repository.cloneUrl(), repository.owner(), repository.name(), repository.defaultBranch(),
                ciphertext, nonce, Timestamp.from(repository.registeredAt()), repository.repositoryId());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO spec_source_repositories (repository_id, clone_url, owner, name, default_branch,
                        credential_ciphertext, credential_nonce, registered_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    repository.repositoryId(), repository.cloneUrl(), repository.owner(), repository.name(),
                    repository.defaultBranch(), ciphertext, nonce, Timestamp.from(repository.registeredAt()));
        }
    }

    @Override
    public Optional<RemoteRepository> find(String repositoryId) {
        List<RemoteRepository> found = jdbc.query(
                "SELECT repository_id, clone_url, owner, name, default_branch, registered_at "
                        + "FROM spec_source_repositories WHERE repository_id = ?",
                rowMapper, repositoryId);
        return found.stream().findFirst();
    }

    @Override
    public List<RemoteRepository> findAll() {
        return jdbc.query(
                "SELECT repository_id, clone_url, owner, name, default_branch, registered_at "
                        + "FROM spec_source_repositories ORDER BY repository_id",
                rowMapper);
    }

    @Override
    public Optional<String> credentialFor(String repositoryId) {
        List<String> found = jdbc.query(
                "SELECT credential_ciphertext, credential_nonce FROM spec_source_repositories "
                        + "WHERE repository_id = ? AND credential_ciphertext IS NOT NULL",
                (rs, rowNum) -> cipher.decrypt(new EncryptedValue(
                        rs.getString("credential_ciphertext"), rs.getString("credential_nonce"))),
                repositoryId);
        return found.stream().findFirst();
    }

    @Override
    public void deregister(String repositoryId) {
        jdbc.update("DELETE FROM spec_source_repositories WHERE repository_id = ?", repositoryId);
    }
}
