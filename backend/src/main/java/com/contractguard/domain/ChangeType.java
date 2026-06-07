package com.contractguard.domain;

/** Normalised category of an OpenAPI contract change. */
public enum ChangeType {
    ENDPOINT_ADDED,
    ENDPOINT_REMOVED,
    ENDPOINT_RENAMED,
    PROPERTY_ADDED,
    PROPERTY_REMOVED,
    PROPERTY_RENAMED,
    PROPERTY_TYPE_CHANGED,
    PROPERTY_REQUIRED_CHANGED,
    ENUM_VALUE_ADDED,
    ENUM_VALUE_REMOVED,
    UNKNOWN_CHANGE
}
