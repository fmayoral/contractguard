package com.contractguard.adapter.web;

import com.contractguard.domain.ContractGuardException;
import com.contractguard.domain.FailureCategory;
import com.contractguard.domain.RunFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Consistent RFC 7807 problem-details errors (§13). Every body states what
 * failed, whether the repository was mutated, and how to proceed (§19).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ContractGuardException.class)
    public ProblemDetail handleTypedFailure(ContractGuardException exception) {
        RunFailure failure = exception.failure();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                statusFor(failure.category()), failure.message());
        problem.setTitle(failure.category().name());
        problem.setProperty("category", failure.category().name());
        problem.setProperty("mutationOccurred", failure.mutationOccurred());
        problem.setProperty("remediation", failure.remediation());
        if (failure.artifactId() != null) {
            problem.setProperty("artifactId", failure.artifactId());
        }
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "request body validation failed");
        problem.setTitle("INVALID_REQUEST");
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception exception) {
        log.error("unhandled API error", exception);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "unexpected server error");
        problem.setTitle(FailureCategory.INTERNAL_ERROR.name());
        problem.setProperty("category", FailureCategory.INTERNAL_ERROR.name());
        return problem;
    }

    private static HttpStatus statusFor(FailureCategory category) {
        return switch (category) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ILLEGAL_STATE, APPROVAL_MISMATCH, REPOSITORY_BUSY, DIRTY_REPOSITORY -> HttpStatus.CONFLICT;
            case LLM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
