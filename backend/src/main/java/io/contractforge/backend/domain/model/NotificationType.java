package io.contractforge.backend.domain.model;

public enum NotificationType {
    RECOMMENDATION;

    public String value() {
        return name();
    }

    public static NotificationType fromValue(String value) {
        return valueOf(value);
    }
}
