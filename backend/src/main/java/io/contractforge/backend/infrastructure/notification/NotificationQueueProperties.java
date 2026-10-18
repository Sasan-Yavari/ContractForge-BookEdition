package io.contractforge.backend.infrastructure.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contractforge.notification.queue")
public record NotificationQueueProperties(String queueUrl, String dlqUrl, int pollSeconds) {
}
