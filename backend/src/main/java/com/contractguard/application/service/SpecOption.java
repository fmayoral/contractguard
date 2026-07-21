package com.contractguard.application.service;

/**
 * One specification file offered at run setup, regardless of where it came
 * from (FR-043, ADR-0012). {@code id} is the qualified identifier
 * {@link RunService} resolves back to a file: {@code local:<name>},
 * {@code upload:<name>} or {@code source:<sourceId>:<name>}.
 */
public record SpecOption(String id, String label, SpecOrigin origin, String sourceId) {

    public enum SpecOrigin {
        LOCAL, UPLOADED, SPEC_SOURCE
    }
}
