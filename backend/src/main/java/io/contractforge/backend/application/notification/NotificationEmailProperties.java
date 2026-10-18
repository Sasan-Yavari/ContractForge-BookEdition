package io.contractforge.backend.application.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contractforge.notification.email")
public record NotificationEmailProperties(boolean enabled) { }
