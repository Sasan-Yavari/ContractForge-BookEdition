package io.contractforge.backend.domain.model;

public enum NotificationChannel {
    EMAIL;

    public static NotificationChannel fromValue(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("Unsupported notification channel", exception);
        }
    }
}
