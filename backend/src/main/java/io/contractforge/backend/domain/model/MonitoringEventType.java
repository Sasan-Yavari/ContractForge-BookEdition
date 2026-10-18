package io.contractforge.backend.domain.model;

/** Closed monitoring event vocabulary. */
public enum MonitoringEventType {
    CONTRACT_EXPIRING,
    CONTRACT_RENEWING,
    PAYMENT_DUE,
    CANCELLATION_DEADLINE,
    PRICE_CHANGE,
    WARRANTY_EXPIRING,
    REQUIREMENT,
    OTHER;

    public String value() {
        return name();
    }

    public static MonitoringEventType fromValue(String value) {
        return valueOf(value);
    }
}
