package com.contractguard.application.port;

import java.time.Duration;

/**
 * Runs one allow-listed validation command (FR-016). Command keys map to
 * fixed argument arrays inside the adapter; free-form commands cannot exist.
 */
public interface BuildValidationPort {

    BuildResult run(String repositoryId, String commandKey);

    /**
     * @param timedOut  true when the command was killed at the timeout
     * @param truncated true when output exceeded the capture limit
     */
    record BuildResult(int exitCode, Duration duration, String output,
            boolean truncated, boolean timedOut) {
    }
}
