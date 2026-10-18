package io.contractforge.backend.application.notification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned worker timing and retry policy; values are required before enabled delivery. */
@ConfigurationProperties(prefix = "contractforge.notification.delivery")
public record NotificationDeliveryProperties(Duration leaseDuration, Integer maxAttempts) {
    public boolean configured() {
        return leaseDuration != null && !leaseDuration.isZero() && !leaseDuration.isNegative()
                && maxAttempts != null && maxAttempts > 0;
    }
}
