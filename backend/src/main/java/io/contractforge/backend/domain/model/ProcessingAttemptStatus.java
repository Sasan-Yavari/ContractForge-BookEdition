package io.contractforge.backend.domain.model;

/** Persisted application processing-attempt lifecycle values. */
public enum ProcessingAttemptStatus {
    RUNNING,
    SUCCEEDED,
    FAILED;

    public String value() {
        return name();
    }

    public static ProcessingAttemptStatus fromValue(String value) {
        return valueOf(value);
    }
}
