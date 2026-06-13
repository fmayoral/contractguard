package com.contractguard.domain;

/**
 * Deterministic classification rules (FR-005). These rules are authoritative;
 * the LLM may explain a classification but can never change it. Reason codes
 * are stable machine-readable identifiers referenced by reports and the UI.
 */
public final class ClassificationPolicy {

    public record Result(Classification classification, String reason) {
    }

    private ClassificationPolicy() {
    }

    /**
     * @param affectsRequired whether the change concerns a required property
     *                        (an added required property or a property whose
     *                        required flag changed); ignored for other types
     */
    public static Result classify(ChangeType type, boolean affectsRequired) {
        return switch (type) {
            case ENDPOINT_REMOVED -> new Result(Classification.BREAKING, "ENDPOINT_REMOVED_CALLERS_GET_404");
            case ENDPOINT_RENAMED -> new Result(Classification.BREAKING, "ENDPOINT_PATH_CHANGED_CALLERS_GET_404");
            case ENDPOINT_ADDED -> new Result(Classification.NON_BREAKING, "NEW_ENDPOINT_NO_EXISTING_CALLERS");
            case PROPERTY_REMOVED -> new Result(Classification.BREAKING, "PROPERTY_REMOVED_CONSUMERS_READ_NULL");
            case PROPERTY_RENAMED -> new Result(Classification.BREAKING, "PROPERTY_RENAMED_CONSUMERS_READ_NULL");
            case PROPERTY_TYPE_CHANGED -> new Result(Classification.BREAKING, "PROPERTY_TYPE_INCOMPATIBLE");
            case PROPERTY_ADDED -> affectsRequired
                    ? new Result(Classification.POTENTIALLY_BREAKING, "REQUIRED_PROPERTY_ADDED_STRICT_CONSUMERS")
                    : new Result(Classification.NON_BREAKING, "OPTIONAL_PROPERTY_ADDED_IGNORED_BY_CONSUMERS");
            case PROPERTY_REQUIRED_CHANGED ->
                    new Result(Classification.POTENTIALLY_BREAKING, "REQUIRED_FLAG_CHANGED_VALIDATION_DRIFT");
            case ENUM_VALUE_REMOVED -> new Result(Classification.BREAKING, "ENUM_VALUE_REMOVED_HANDLING_UNREACHABLE");
            case ENUM_VALUE_ADDED ->
                    new Result(Classification.POTENTIALLY_BREAKING, "ENUM_VALUE_ADDED_EXHAUSTIVE_SWITCHES_MISS_IT");
            case UNKNOWN_CHANGE -> new Result(Classification.UNKNOWN, "CHANGE_CATEGORY_NOT_ANALYSED");
        };
    }
}
