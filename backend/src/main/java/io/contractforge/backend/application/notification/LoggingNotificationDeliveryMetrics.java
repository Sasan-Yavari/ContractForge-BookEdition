package io.contractforge.backend.application.notification;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Safe operational evidence hook that emits only bounded delivery metadata. */
@Component
@Profile("!test")
public final class LoggingNotificationDeliveryMetrics implements NotificationDeliveryMetrics {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LoggingNotificationDeliveryMetrics.class);

    @Override
    public void record(DeliveryOutcome outcome) {
        LOG.atInfo()
                .addKeyValue("event_type", "notification_delivery_outcome")
                .addKeyValue("delivery_id", outcome.deliveryId())
                .addKeyValue("outcome", outcome.outcome().name())
                .addKeyValue("attempt_count", outcome.attemptCount())
                .addKeyValue("queue_age_ms", outcome.queueAge().toMillis())
                .addKeyValue("delivery_latency_ms", outcome.deliveryLatency().toMillis())
                .log("notification_delivery_outcome");
    }
}
