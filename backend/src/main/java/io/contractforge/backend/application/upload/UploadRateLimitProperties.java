package io.contractforge.backend.application.upload;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contractforge.api.upload-rate-limit")
public record UploadRateLimitProperties(int maximumRequests, Duration window, int maxTrackedUsers) {
    private static final int MAXIMUM_REQUESTS = 1_000;
    private static final int MAX_TRACKED_USERS = 100_000;
    private static final long MAX_TRACKED_ATTEMPTS = 100_000;
    private static final Duration MAXIMUM_WINDOW = Duration.ofDays(1);

    public UploadRateLimitProperties {
        if (maximumRequests < 1 || maximumRequests > MAXIMUM_REQUESTS) {
            throw new IllegalArgumentException("Upload rate limit maximum-requests must be between 1 and "
                    + MAXIMUM_REQUESTS + "; received " + maximumRequests);
        }
        if (window == null || window.isZero() || window.isNegative() || window.compareTo(MAXIMUM_WINDOW) > 0
                || window.toNanos() == 0) {
            throw new IllegalArgumentException("Upload rate limit window must be greater than zero and at most "
                    + MAXIMUM_WINDOW);
        }
        if (maxTrackedUsers < 1 || maxTrackedUsers > MAX_TRACKED_USERS
                || (long) maximumRequests * maxTrackedUsers > MAX_TRACKED_ATTEMPTS) {
            throw new IllegalArgumentException("Upload rate limit tracked-user and request bounds are invalid");
        }
    }
}
