package io.contractforge.backend.domain.model;

/** Safe processing-failure meanings exposed to product clients. */
public enum ProcessingFailureCategory {
    RETRYABLE,
    DETERMINISTIC_DOCUMENT,
    UNKNOWN;

    public String value() {
        return name();
    }
}
