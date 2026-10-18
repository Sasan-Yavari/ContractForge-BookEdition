package io.contractforge.backend.domain.model;

public enum NotificationFailureCode {
    RECIPIENT_UNAVAILABLE,
    RENDERING_FAILED,
    EMAIL_SENDER_UNAVAILABLE,
    EMAIL_REJECTED,
    EMAIL_PROVIDER_UNAVAILABLE,
    DELIVERY_ATTEMPTS_EXHAUSTED
}
