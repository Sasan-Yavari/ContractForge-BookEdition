package io.contractforge.backend.domain.model;

/** Closed contract type vocabulary. */
public enum ContractType {
    ELECTRICITY;

    public String value() {
        return name();
    }

    public static ContractType fromValue(String value) {
        return valueOf(value);
    }
}
