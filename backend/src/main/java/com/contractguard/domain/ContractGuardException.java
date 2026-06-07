package com.contractguard.domain;

import java.util.Objects;

/** Carrier for a typed {@link RunFailure}; the only exception the workflow throws deliberately. */
public class ContractGuardException extends RuntimeException {

    private final transient RunFailure failure;

    public ContractGuardException(RunFailure failure) {
        super(failure.category() + ": " + failure.message());
        this.failure = failure;
    }

    public ContractGuardException(RunFailure failure, Throwable cause) {
        super(failure.category() + ": " + failure.message(), cause);
        this.failure = failure;
    }

    public static ContractGuardException of(FailureCategory category, String message, String remediation) {
        return new ContractGuardException(new RunFailure(category, message, false, null, remediation));
    }

    public RunFailure failure() {
        return Objects.requireNonNull(failure);
    }
}
