package com.contractguard.domain;

/** Deterministic classification of an API contract change. */
public enum Classification {
    BREAKING,
    POTENTIALLY_BREAKING,
    NON_BREAKING,
    UNKNOWN
}
