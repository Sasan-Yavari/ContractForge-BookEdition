package io.contractforge.backend.domain.model;

/** Persisted monitoring event lifecycle values. */
public enum EventStatus {
    ACTIVE,
    COMPLETED,
    DISMISSED,
    CANCELLED;

    public String value() {
        return name();
    }

    public static EventStatus fromValue(String value) {
        return valueOf(value);
    }
}
