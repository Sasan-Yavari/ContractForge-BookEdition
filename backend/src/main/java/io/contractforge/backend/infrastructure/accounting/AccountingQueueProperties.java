package io.contractforge.backend.infrastructure.accounting;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contractforge.accounting.queue")
public record AccountingQueueProperties(String queueUrl, String dlqUrl, int pollSeconds) { }
