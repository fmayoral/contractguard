package com.contractguard.application.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads versioned prompt resources from the classpath (§16). Prompts are
 * immutable build artifacts: name and version are recorded on every LLM
 * trace so any output can be tied to the exact instructions that caused it.
 */
public final class PromptLibrary {

    /** Bump a version suffix, never edit in place, when changing a prompt's contract. */
    public record Prompt(String name, String version, String text) {
    }

    private final Map<String, Prompt> cache = new ConcurrentHashMap<>();

    public Prompt get(String name, String version) {
        return cache.computeIfAbsent(name + "." + version, key -> load(name, version));
    }

    private Prompt load(String name, String version) {
        String resource = "/prompts/%s.%s.md".formatted(name, version);
        try (InputStream in = PromptLibrary.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing prompt resource " + resource);
            }
            return new Prompt(name, version, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot load prompt resource " + resource, e);
        }
    }
}
