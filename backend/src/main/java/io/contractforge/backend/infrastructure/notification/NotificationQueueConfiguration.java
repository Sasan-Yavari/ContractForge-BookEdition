package io.contractforge.backend.infrastructure.notification;

import io.contractforge.backend.application.notification.NotificationEmailProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({NotificationEmailProperties.class, NotificationQueueProperties.class})
public class NotificationQueueConfiguration { }
