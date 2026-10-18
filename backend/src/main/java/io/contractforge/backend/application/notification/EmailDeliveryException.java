package io.contractforge.backend.application.notification;

/** Bounded application classification for an email provider failure. */
public final class EmailDeliveryException extends RuntimeException {
    private final boolean retryable;
    private final String failureCode;

    public EmailDeliveryException(boolean retryable, String failureCode) {
        super("Email delivery failed");
        this.retryable = retryable;
        this.failureCode = failureCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public String failureCode() {
        return failureCode;
    }
}
