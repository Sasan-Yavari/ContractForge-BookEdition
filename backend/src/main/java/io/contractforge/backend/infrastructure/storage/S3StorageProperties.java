package io.contractforge.backend.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contractforge.storage.s3")
public record S3StorageProperties(String bucket, String region, String trustedObjectKeyPrefix) { }
