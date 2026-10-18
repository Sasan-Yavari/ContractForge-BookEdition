package io.contractforge.backend.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Temporary account-linked state needed to fence and recover deletion. */
public record AccountDeletionJob(UUID id, UUID userId, AccountDeletionPhase phase,
                                 OffsetDateTime uploadCapabilitiesExpireAt,
                                 OffsetDateTime identityFenceUntil, OffsetDateTime availableAt,
                                 UUID workerToken) {
    public AccountDeletionJob {
        if (id == null || userId == null || phase == null
                || uploadCapabilitiesExpireAt == null || identityFenceUntil == null || availableAt == null) {
            throw new IllegalArgumentException("Account deletion job is incomplete");
        }
    }
}
