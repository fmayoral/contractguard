package com.contractguard.config;

import com.contractguard.adapter.git.RemoteGitCliAdapter;
import com.contractguard.adapter.github.GitHubPullRequestAdapter;
import com.contractguard.adapter.persistence.JdbcRemoteRepositoryRegistry;
import com.contractguard.adapter.persistence.JdbcSpecSourceRegistry;
import com.contractguard.adapter.process.ProcessRunner;
import com.contractguard.adapter.security.AesGcmCredentialCipher;
import com.contractguard.application.port.PullRequestPort;
import com.contractguard.application.port.RemoteGitPort;
import com.contractguard.application.port.RemoteRepositoryRegistry;
import com.contractguard.application.port.SpecSourceRegistry;
import com.contractguard.application.service.RemoteRepositoryService;
import com.contractguard.application.service.SpecSourceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/**
 * Wires registered remote repositories (FR-027: consumer repos to clone/push/open PRs against)
 * and spec sources (FR-043: read-only spec repos). Both reuse {@link RemoteGitCliAdapter} and
 * {@link AesGcmCredentialCipher} but keep separate registries/cache roots -- a spec source must
 * never become a selectable, analysable consumer repository (ADR-0012).
 */
@Configuration
public class RemoteRepositoryConfiguration {

    @Bean
    public AesGcmCredentialCipher credentialCipher(ContractGuardProperties properties) {
        return new AesGcmCredentialCipher(properties.remote().credentialKey());
    }

    @Bean
    public RemoteRepositoryRegistry remoteRepositoryRegistry(JdbcTemplate jdbc, AesGcmCredentialCipher cipher) {
        return new JdbcRemoteRepositoryRegistry(jdbc, cipher);
    }

    @Bean
    public RemoteGitPort remoteGitPort(ProcessRunner processRunner, ContractGuardProperties properties) {
        return new RemoteGitCliAdapter(StorageLayout.remoteCache(properties), processRunner);
    }

    @Bean
    public PullRequestPort pullRequestPort() {
        return new GitHubPullRequestAdapter();
    }

    @Bean
    public RemoteRepositoryService remoteRepositoryService(RemoteRepositoryRegistry registry,
            RemoteGitPort remoteGit, Clock clock) {
        return new RemoteRepositoryService(registry, remoteGit, clock);
    }

    @Bean
    public SpecSourceRegistry specSourceRegistry(JdbcTemplate jdbc, AesGcmCredentialCipher cipher) {
        return new JdbcSpecSourceRegistry(jdbc, cipher);
    }

    @Bean
    public SpecSourceService specSourceService(SpecSourceRegistry registry, ProcessRunner processRunner,
            ContractGuardProperties properties, Clock clock) {
        // A second RemoteGitCliAdapter instance with its own cache root -- reusing the class costs
        // nothing new, but it must stay a separate RemoteGitPort from the consumer one (remoteGitPort
        // bean above) since spec sources are never pushed to and never become a WorkspacePolicy root.
        RemoteGitPort specSourceGit = new RemoteGitCliAdapter(StorageLayout.specSourceCache(properties), processRunner);
        return new SpecSourceService(registry, specSourceGit, StorageLayout.specSourceCache(properties), clock);
    }
}
