package io.contractforge.backend.application.upload;

public final class UploadInitiationRateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    public UploadInitiationRateLimitExceededException(long retryAfterSeconds) {
        super();
        if (retryAfterSeconds < 1) {
            throw new IllegalArgumentException("retryAfterSeconds must be positive");
        }
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
