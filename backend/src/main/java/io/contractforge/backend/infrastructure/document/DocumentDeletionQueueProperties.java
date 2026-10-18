package io.contractforge.backend.infrastructure.document;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contractforge.document-deletion.queue")
public record DocumentDeletionQueueProperties(String queueUrl, int pollSeconds) { }
