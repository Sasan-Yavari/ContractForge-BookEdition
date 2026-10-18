package io.contractforge.backend.infrastructure.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contractforge.upload.ingestion.queue")
public record UploadIngestionQueueProperties(String queueUrl, int pollSeconds, String bucket) {
    public UploadIngestionQueueProperties {
        if (queueUrl == null || queueUrl.isBlank() || bucket == null || bucket.isBlank() || pollSeconds < 1) {
            throw new IllegalArgumentException("Production ingestion queue configuration is incomplete");
        }
    }
}
