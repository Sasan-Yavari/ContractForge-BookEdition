package io.contractforge.backend.domain.model;

/** Closed contract pricing vocabulary. */
public enum ContractPricingModel {
    FIXED,
    VARIABLE,
    DYNAMIC;

    public String value() {
        return name();
    }

    public static ContractPricingModel fromValue(String value) {
        return valueOf(value);
    }
}
