package com.contractguard.testsupport;

import com.contractguard.application.port.SpecSourceRegistry;
import com.contractguard.domain.RemoteRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** In-memory spec-source registry for application-service tests. */
public class InMemorySpecSourceRegistry implements SpecSourceRegistry {

    private final Map<String, RemoteRepository> sources = new LinkedHashMap<>();
    private final Map<String, String> credentials = new LinkedHashMap<>();

    @Override
    public void register(RemoteRepository repository, String token) {
        sources.put(repository.repositoryId(), repository);
        if (token != null && !token.isBlank()) {
            credentials.put(repository.repositoryId(), token);
        } else {
            credentials.remove(repository.repositoryId());
        }
    }

    @Override
    public Optional<RemoteRepository> find(String repositoryId) {
        return Optional.ofNullable(sources.get(repositoryId));
    }

    @Override
    public Optional<String> credentialFor(String repositoryId) {
        return Optional.ofNullable(credentials.get(repositoryId));
    }

    @Override
    public List<RemoteRepository> findAll() {
        return List.copyOf(sources.values());
    }
}
