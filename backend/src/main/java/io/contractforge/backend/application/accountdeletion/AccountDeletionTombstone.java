package io.contractforge.backend.application.accountdeletion;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Minimal external evidence used to keep completed deletion identities fenced across RDS restores. */
public record AccountDeletionTombstone(UUID deletionId, UUID userId, String sourceDbResourceId,
                                       List<String> identityFingerprints,
                                       State state, OffsetDateTime fencedAt, OffsetDateTime completedAt,
                                       OffsetDateTime lastRecoveryCheckAt,
                                       OffsetDateTime oldestRecoverablePointAt,
                                       int recoverablePointCount) {
    public AccountDeletionTombstone {
        if (deletionId == null || userId == null || sourceDbResourceId == null || sourceDbResourceId.isBlank()
                || identityFingerprints == null || identityFingerprints.isEmpty()
                || state == null || fencedAt == null || recoverablePointCount < 0) {
            throw new IllegalArgumentException("Account deletion tombstone is incomplete");
        }
        identityFingerprints = List.copyOf(identityFingerprints);
        if (state == State.COMPLETED && completedAt == null) {
            throw new IllegalArgumentException("Completed account deletion tombstone has no completion time");
        }
    }

    public enum State {
        IN_PROGRESS,
        COMPLETED
    }
}
