package io.contractforge.backend.domain.model;

/** Persisted Document lifecycle values. */
public enum DocumentStatus {
    PROCESSING,
    SUCCESS,
    FAILED;

    public String value() {
        return name();
    }

    public static DocumentStatus fromValue(String value) {
        return valueOf(value);
    }
}
