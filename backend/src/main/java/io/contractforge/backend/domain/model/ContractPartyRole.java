package io.contractforge.backend.domain.model;

/** Closed party-role vocabulary for extracted and reviewed contracts. */
public enum ContractPartyRole {
    CUSTOMER,
    PROVIDER,
    BROKER;

    public String value() {
        return name();
    }

    public static ContractPartyRole fromValue(String value) {
        return valueOf(value);
    }
}
