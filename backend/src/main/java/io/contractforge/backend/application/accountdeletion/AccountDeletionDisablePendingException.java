package io.contractforge.backend.application.accountdeletion;

/** The durable deletion job is retryable, but immediate Cognito disable has not yet succeeded. */
public final class AccountDeletionDisablePendingException extends RuntimeException {
    private final int retryAfterSeconds;

    public AccountDeletionDisablePendingException(int retryAfterSeconds) {
        super("Account deletion is pending identity-provider disable");
        if (retryAfterSeconds < 1) throw new IllegalArgumentException("Retry delay must be positive");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
