package io.contractforge.backend.domain.model;

/** Persisted contract lifecycle values. */
public enum ContractStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED;

    public String value() {
        return name();
    }

    public static ContractStatus fromValue(String value) {
        return valueOf(value);
    }
}
