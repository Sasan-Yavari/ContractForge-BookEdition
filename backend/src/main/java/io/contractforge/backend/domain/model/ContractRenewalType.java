package io.contractforge.backend.domain.model;

/** Closed contract renewal vocabulary. */
public enum ContractRenewalType {
    FIXED_TERM,
    AUTO_RENEW,
    OPEN_ENDED;

    public String value() {
        return name();
    }

    public static ContractRenewalType fromValue(String value) {
        return valueOf(value);
    }
}
