package io.contractforge.backend.application.accountdeletion;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contractforge.account-deletion")
public record AccountDeletionProperties(Duration identityFenceDuration, Duration uploadWriteGrace) {
    public AccountDeletionProperties {
        if (identityFenceDuration == null || identityFenceDuration.compareTo(Duration.ofMinutes(70)) < 0
                || uploadWriteGrace == null || uploadWriteGrace.isNegative()) {
            throw new IllegalArgumentException("Account deletion fencing durations are invalid");
        }
    }
}
