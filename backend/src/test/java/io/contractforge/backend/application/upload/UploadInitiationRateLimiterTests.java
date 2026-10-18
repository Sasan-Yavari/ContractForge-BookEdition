package io.contractforge.backend.application.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class UploadInitiationRateLimiterTests {
    private static final AuthenticatedApplicationUser OWNER = user("30000000-0000-0000-0000-000000000001");
    private static final AuthenticatedApplicationUser OTHER = user("30000000-0000-0000-0000-000000000002");

    @Test
    void allowsConfiguredRequestsAndRejectsTheNextWithTheRemainingWindow() {
        AtomicLong now = new AtomicLong();
        UploadInitiationRateLimiter limiter = limiter(2, Duration.ofSeconds(60), 10, now);

        limiter.acquire(OWNER);
        limiter.acquire(OWNER);

        assertThatThrownBy(() -> limiter.acquire(OWNER))
                .isInstanceOfSatisfying(UploadInitiationRateLimitExceededException.class,
                        exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(60));
    }

    @Test
    void slidingWindowResetsAtTheExactExpiryAndAllowsOtherUsersIndependently() {
        AtomicLong now = new AtomicLong();
        UploadInitiationRateLimiter limiter = limiter(1, Duration.ofSeconds(60), 10, now);

        limiter.acquire(OWNER);
        limiter.acquire(OTHER);
        now.set(Duration.ofMinutes(1).toNanos() - 1);
        assertThatThrownBy(() -> limiter.acquire(OWNER))
                .isInstanceOfSatisfying(UploadInitiationRateLimitExceededException.class,
                        exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(1));
        now.incrementAndGet();
        limiter.acquire(OWNER);
    }

    @Test
    void boundsTrackedIdentityStateAndRecoversCapacityWhenTheOldestWindowExpires() {
        AtomicLong now = new AtomicLong();
        UploadInitiationRateLimiter limiter = limiter(2, Duration.ofSeconds(60), 1, now);

        limiter.acquire(OWNER);
        assertThatThrownBy(() -> limiter.acquire(OTHER))
                .isInstanceOfSatisfying(UploadRateLimitCapacityExceededException.class,
                        exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(60));
        now.set(Duration.ofSeconds(60).toNanos());

        limiter.acquire(OTHER);
    }

    @Test
    void capacityRetryAfterCoversTheFullWindowWhenAUserHasLaterAttempts() {
        AtomicLong now = new AtomicLong();
        UploadInitiationRateLimiter limiter = limiter(2, Duration.ofSeconds(60), 1, now);

        limiter.acquire(OWNER);
        now.set(Duration.ofSeconds(30).toNanos());
        limiter.acquire(OWNER);

        assertThatThrownBy(() -> limiter.acquire(OTHER))
                .isInstanceOfSatisfying(UploadRateLimitCapacityExceededException.class,
                        exception -> assertThat(exception.retryAfterSeconds()).isEqualTo(60));
    }

    @Test
    void concurrentRequestsCannotExceedThePerUserLimit() throws Exception {
        int requestCount = 32;
        int allowedLimit = 7;
        UploadInitiationRateLimiter limiter = limiter(allowedLimit, Duration.ofMinutes(1), 10,
                new AtomicLong());
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            List<Future<?>> requests = new ArrayList<>();
            for (int index = 0; index < requestCount; index++) {
                requests.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        limiter.acquire(OWNER);
                        allowed.incrementAndGet();
                    } catch (UploadInitiationRateLimitExceededException exceeded) {
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> request : requests) {
                request.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(allowed).hasValue(allowedLimit);
        assertThat(rejected).hasValue(requestCount - allowedLimit);
    }

    @Test
    void rejectsInvalidOrUnboundedConfigurationAtConstruction() {
        assertThatThrownBy(() -> new UploadRateLimitProperties(0, Duration.ofMinutes(1), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UploadRateLimitProperties(5, Duration.ZERO, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UploadRateLimitProperties(100, Duration.ofMinutes(1), 10_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void springUsesTheProductionConstructorWhenTheTestClockConstructorExists() {
        new ApplicationContextRunner()
                .withUserConfiguration(RateLimiterConfiguration.class)
                .withBean(UploadRateLimitProperties.class,
                        () -> new UploadRateLimitProperties(5, Duration.ofMinutes(1), 10))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(UploadInitiationRateLimiter.class));
    }

    private UploadInitiationRateLimiter limiter(int maximumRequests, Duration window, int maxTrackedUsers,
                                                 AtomicLong now) {
        return new UploadInitiationRateLimiter(
                new UploadRateLimitProperties(maximumRequests, window, maxTrackedUsers), now::get);
    }

    private static AuthenticatedApplicationUser user(String userId) {
        return new AuthenticatedApplicationUser(UUID.fromString(userId));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(UploadInitiationRateLimiter.class)
    static class RateLimiterConfiguration { }
}
