package io.contractforge.backend.application.accountdeletion;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** A tombstone is releasable only after every observed recovery point starts after deletion completion. */
public final class AccountDeletionRecoveryPointPolicy {
    public Assessment assess(AccountDeletionTombstone tombstone, List<RecoveryPoint> recoveryPoints) {
        if (tombstone == null || tombstone.state() != AccountDeletionTombstone.State.COMPLETED
                || tombstone.completedAt() == null || recoveryPoints == null) {
            throw new IllegalArgumentException("Completed deletion and recovery-point inventory are required");
        }
        int holdingCount = 0;
        OffsetDateTime oldest = null;
        for (RecoveryPoint point : recoveryPoints) {
            if (point != null && (point.kind() == RecoveryPointKind.MANUAL_SNAPSHOT
                    || point.kind() == RecoveryPointKind.AWS_BACKUP_SNAPSHOT)) {
                // Snapshot creation time does not prove the database state was post-deletion.
                holdingCount++;
                continue;
            }
            if (point == null || point.earliestRestorableAt() == null || point.latestRestorableAt() == null
                    || point.latestRestorableAt().isBefore(point.earliestRestorableAt())) {
                holdingCount++;
                continue;
            }
            if (!point.earliestRestorableAt().isAfter(tombstone.completedAt())) {
                holdingCount++;
                if (oldest == null || point.earliestRestorableAt().isBefore(oldest)) {
                    oldest = point.earliestRestorableAt();
                }
            }
        }
        return new Assessment(holdingCount, Optional.ofNullable(oldest));
    }

    public enum RecoveryPointKind {
        AUTOMATED_BACKUP,
        AUTOMATED_BACKUP_IDENTITY_UNKNOWN,
        REPLICATED_AUTOMATED_BACKUP,
        AUTOMATED_SNAPSHOT,
        MANUAL_SNAPSHOT,
        AWS_BACKUP_SNAPSHOT,
        SNAPSHOT_IDENTITY_UNKNOWN
    }

    public record RecoveryPoint(RecoveryPointKind kind, OffsetDateTime earliestRestorableAt,
                                OffsetDateTime latestRestorableAt) {
        public RecoveryPoint {
            if (kind == null) throw new IllegalArgumentException("Recovery-point kind is required");
        }
    }

    public record Assessment(int holdingRecoveryPointCount, Optional<OffsetDateTime> oldestRecoverablePointAt) {
        public Assessment {
            if (holdingRecoveryPointCount < 0 || oldestRecoverablePointAt == null
                    || (holdingRecoveryPointCount == 0 && oldestRecoverablePointAt.isPresent())) {
                throw new IllegalArgumentException("Recovery assessment is invalid");
            }
        }

        public boolean mayRelease() {
            return holdingRecoveryPointCount == 0;
        }
    }
}
