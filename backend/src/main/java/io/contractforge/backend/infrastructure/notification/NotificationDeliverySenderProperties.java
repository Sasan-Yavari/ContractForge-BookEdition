package io.contractforge.backend.infrastructure.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contractforge.notification.email")
public record NotificationDeliverySenderProperties(String senderAddress, String region) {
    public NotificationDeliverySenderProperties {
        if (region == null || region.isBlank()) {
            region = System.getenv().getOrDefault("AWS_REGION", "eu-west-1");
        }
    }
}
