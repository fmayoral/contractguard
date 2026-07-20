package com.contractguard.adapter.persistence;

import com.contractguard.adapter.security.AesGcmCredentialCipher;
import com.contractguard.adapter.security.AesGcmCredentialCipher.EncryptedValue;
import com.contractguard.application.port.RemoteRepositoryRegistry;
import com.contractguard.domain.RemoteRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** JDBC-backed remote repository registry; credentials are encrypted before every write (ADR-0007). */
public class JdbcRemoteRepositoryRegistry implements RemoteRepositoryRegistry {

    private final JdbcTemplate jdbc;
    private final AesGcmCredentialCipher cipher;

    private final RowMapper<RemoteRepository> rowMapper = (rs, rowNum) -> new RemoteRepository(
            rs.getString("repository_id"), rs.getString("clone_url"), rs.getString("owner"),
            rs.getString("name"), rs.getString("default_branch"), rs.getTimestamp("registered_at").toInstant());

    public JdbcRemoteRepositoryRegistry(JdbcTemplate jdbc, AesGcmCredentialCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public void register(RemoteRepository repository, String token) {
        EncryptedValue encrypted = cipher.encrypt(token);
        int updated = jdbc.update("""
                UPDATE remote_repositories SET clone_url = ?, owner = ?, name = ?, default_branch = ?,
                    credential_ciphertext = ?, credential_nonce = ?, registered_at = ?
                WHERE repository_id = ?""",
                repository.cloneUrl(), repository.owner(), repository.name(), repository.defaultBranch(),
                encrypted.ciphertextBase64(), encrypted.nonceBase64(),
                Timestamp.from(repository.registeredAt()), repository.repositoryId());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO remote_repositories (repository_id, clone_url, owner, name, default_branch,
                        credential_ciphertext, credential_nonce, registered_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    repository.repositoryId(), repository.cloneUrl(), repository.owner(), repository.name(),
                    repository.defaultBranch(), encrypted.ciphertextBase64(), encrypted.nonceBase64(),
                    Timestamp.from(repository.registeredAt()));
        }
    }

    @Override
    public Optional<RemoteRepository> find(String repositoryId) {
        List<RemoteRepository> found = jdbc.query(
                "SELECT repository_id, clone_url, owner, name, default_branch, registered_at "
                        + "FROM remote_repositories WHERE repository_id = ?",
                rowMapper, repositoryId);
        return found.stream().findFirst();
    }

    @Override
    public List<RemoteRepository> findAll() {
        return jdbc.query(
                "SELECT repository_id, clone_url, owner, name, default_branch, registered_at "
                        + "FROM remote_repositories ORDER BY repository_id",
                rowMapper);
    }

    @Override
    public Optional<String> credentialFor(String repositoryId) {
        List<String> found = jdbc.query(
                "SELECT credential_ciphertext, credential_nonce FROM remote_repositories WHERE repository_id = ?",
                (rs, rowNum) -> cipher.decrypt(new EncryptedValue(
                        rs.getString("credential_ciphertext"), rs.getString("credential_nonce"))),
                repositoryId);
        return found.stream().findFirst();
    }
}
