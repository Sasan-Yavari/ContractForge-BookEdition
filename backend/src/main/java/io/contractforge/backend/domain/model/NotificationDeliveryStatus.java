package io.contractforge.backend.domain.model;

public enum NotificationDeliveryStatus {
    QUEUED, IN_FLIGHT, DELIVERED, FAILED;

    public static NotificationDeliveryStatus fromValue(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("Unsupported notification delivery status", exception);
        }
    }
}
