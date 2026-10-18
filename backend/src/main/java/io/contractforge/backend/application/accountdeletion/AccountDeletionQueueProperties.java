package io.contractforge.backend.application.accountdeletion;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contractforge.account-deletion.queue")
public record AccountDeletionQueueProperties(String queueUrl, int pollSeconds, int dispatchBatchSize,
                                              Duration dispatchLease, Duration workerLease,
                                              Duration visibilityTimeout) {
    public AccountDeletionQueueProperties {
        if (pollSeconds < 1 || dispatchBatchSize < 1 || dispatchLease == null || dispatchLease.isNegative()
                || dispatchLease.isZero() || workerLease == null || workerLease.isNegative() || workerLease.isZero()
                || visibilityTimeout == null || visibilityTimeout.isNegative() || visibilityTimeout.isZero()
                || workerLease.plusSeconds(30).compareTo(visibilityTimeout) > 0) {
            throw new IllegalArgumentException("Account-deletion queue settings are invalid");
        }
    }
}
