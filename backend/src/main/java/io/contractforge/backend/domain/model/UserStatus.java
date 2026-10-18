package io.contractforge.backend.domain.model;

/** Persisted user lifecycle values. */
public enum UserStatus {
    ACTIVE,
    DISABLED;

    public String value() {
        return name();
    }

    public static UserStatus fromValue(String value) {
        return valueOf(value);
    }
}
