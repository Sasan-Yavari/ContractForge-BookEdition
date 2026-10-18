package io.contractforge.backend.application.upload;

import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Process-local sliding-window admission control for the cost-bearing upload-initiation use case. */
@Service
@Profile("!test")
public final class UploadInitiationRateLimiter {
    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final Comparator<Expiration> EXPIRATION_ORDER =
            (left, right) -> Long.compare(left.deadlineNanos() - right.deadlineNanos(), 0L);

    private final UploadRateLimitProperties properties;
    private final long windowNanos;
    private final LongSupplier nanoTime;
    private final Map<UUID, UserWindow> windows = new HashMap<>();
    private final PriorityQueue<Expiration> expirations = new PriorityQueue<>(EXPIRATION_ORDER);
    private long nextTicket;

    @Autowired
    public UploadInitiationRateLimiter(UploadRateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    UploadInitiationRateLimiter(UploadRateLimitProperties properties, LongSupplier nanoTime) {
        this.properties = properties;
        this.windowNanos = properties.window().toNanos();
        this.nanoTime = nanoTime;
    }

    public synchronized void acquire(AuthenticatedApplicationUser user) {
        if (user == null || user.userId() == null) {
            throw new IllegalArgumentException("An authenticated application user is required");
        }

        long now = nanoTime.getAsLong();
        removeExpiredWindows(now);
        UUID userId = user.userId();
        UserWindow window = windows.get(userId);
        if (window == null) {
            if (windows.size() >= properties.maxTrackedUsers()) {
                if (expirations.isEmpty()) {
                    throw new IllegalStateException("Tracked upload rate-limit state has no expiry");
                }
                throw capacityExceeded(now + windowNanos, now);
            }
            window = new UserWindow();
            windows.put(userId, window);
        }

        if (window.attempts.size() >= properties.maximumRequests()) {
            long oldestAttempt = window.attempts.peekFirst();
            throw rateLimited(oldestAttempt + windowNanos, now);
        }

        boolean firstAttempt = window.attempts.isEmpty();
        window.attempts.addLast(now);
        if (firstAttempt) {
            scheduleExpiry(userId, window, now + windowNanos);
        }
    }

    private void removeExpiredWindows(long now) {
        while (!expirations.isEmpty() && hasExpired(expirations.peek().deadlineNanos(), now)) {
            Expiration expiration = expirations.remove();
            UserWindow window = windows.get(expiration.userId());
            if (window == null || window.ticket != expiration.ticket()) {
                continue;
            }
            while (!window.attempts.isEmpty()
                    && hasExpired(window.attempts.peekFirst() + windowNanos, now)) {
                window.attempts.removeFirst();
            }
            if (window.attempts.isEmpty()) {
                windows.remove(expiration.userId());
            } else {
                scheduleExpiry(expiration.userId(), window, window.attempts.peekFirst() + windowNanos);
            }
        }
    }

    private void scheduleExpiry(UUID userId, UserWindow window, long deadlineNanos) {
        window.ticket = ++nextTicket;
        expirations.add(new Expiration(deadlineNanos, userId, window.ticket));
    }

    private UploadInitiationRateLimitExceededException rateLimited(long deadlineNanos, long now) {
        return new UploadInitiationRateLimitExceededException(retryAfterSeconds(deadlineNanos, now));
    }

    private UploadRateLimitCapacityExceededException capacityExceeded(long deadlineNanos, long now) {
        return new UploadRateLimitCapacityExceededException(retryAfterSeconds(deadlineNanos, now));
    }

    private long retryAfterSeconds(long deadlineNanos, long now) {
        long remainingNanos = Math.max(1, deadlineNanos - now);
        return Math.max(1, (remainingNanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND);
    }

    private boolean hasExpired(long deadlineNanos, long now) {
        return now - deadlineNanos >= 0;
    }

    private static final class UserWindow {
        private final ArrayDeque<Long> attempts = new ArrayDeque<>();
        private long ticket;
    }

    private record Expiration(long deadlineNanos, UUID userId, long ticket) { }
}
