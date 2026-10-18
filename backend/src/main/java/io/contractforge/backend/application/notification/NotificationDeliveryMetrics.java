package io.contractforge.backend.application.notification;

import java.time.Duration;
import java.util.UUID;

/** Minimal application metrics hook; implementations must emit operational metadata only. */
public interface NotificationDeliveryMetrics {
    void record(DeliveryOutcome outcome);

    record DeliveryOutcome(UUID deliveryId, Outcome outcome, int attemptCount,
                           Duration queueAge, Duration deliveryLatency) {
    }

    enum Outcome {
        ACKNOWLEDGED, DELIVERED, RETRYABLE_FAILURE, FAILED, DLQ
    }
}
