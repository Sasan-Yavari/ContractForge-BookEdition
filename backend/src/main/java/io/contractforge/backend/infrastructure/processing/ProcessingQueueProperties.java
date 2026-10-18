package io.contractforge.backend.infrastructure.processing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contractforge.processing.queue")
public record ProcessingQueueProperties(String queueUrl, String dlqUrl, int pollSeconds) { }
