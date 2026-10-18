package io.contractforge.backend.application.accountdeletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.contractforge.backend.application.accountdeletion.AccountDeletionRecoveryPointPolicy.RecoveryPoint;
import io.contractforge.backend.application.accountdeletion.AccountDeletionRecoveryPointPolicy.RecoveryPointKind;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountDeletionRecoveryPointPolicyTests {
    private static final OffsetDateTime COMPLETED = OffsetDateTime.parse("2026-09-01T12:00:00Z");
    private final AccountDeletionRecoveryPointPolicy policy = new AccountDeletionRecoveryPointPolicy();

    @Test
    void releasesOnlyWhenInventoryContainsNoPointThatCanRestorePreDeletionState() {
        var tombstone = completedTombstone();

        assertThat(policy.assess(tombstone, List.of()).mayRelease()).isTrue();
        assertThat(policy.assess(tombstone, List.of(automated("2026-09-01T12:00:01Z",
                "2026-09-02T12:00:00Z"))).mayRelease()).isTrue();
    }

    @Test
    void holdsForRestoreWindowsThatTouchOrPrecedeDeletionCompletion() {
        var tombstone = completedTombstone();

        var before = policy.assess(tombstone, List.of(automated("2026-09-01T11:59:59Z",
                "2026-09-01T12:30:00Z")));
        var atBoundary = policy.assess(tombstone, List.of(automated("2026-09-01T12:00:00Z",
                "2026-09-01T12:30:00Z")));

        assertThat(before.mayRelease()).isFalse();
        assertThat(before.holdingRecoveryPointCount()).isEqualTo(1);
        assertThat(before.oldestRecoverablePointAt()).contains(OffsetDateTime.parse("2026-09-01T11:59:59Z"));
        assertThat(atBoundary.mayRelease()).isFalse();
    }

    @Test
    void failsClosedForUnknownOrMalformedRecoveryInventory() {
        var tombstone = completedTombstone();
        var unknown = new RecoveryPoint(RecoveryPointKind.AUTOMATED_BACKUP_IDENTITY_UNKNOWN, null, null);
        var missingWindow = new RecoveryPoint(RecoveryPointKind.AUTOMATED_BACKUP, null, null);
        var invertedWindow = new RecoveryPoint(RecoveryPointKind.AUTOMATED_BACKUP,
                OffsetDateTime.parse("2026-09-02T00:00:00Z"), OffsetDateTime.parse("2026-09-01T00:00:00Z"));

        assertThat(policy.assess(tombstone, List.of(unknown, missingWindow, invertedWindow))
                .holdingRecoveryPointCount()).isEqualTo(3);
    }

    @Test
    void manualAndAwsBackupSnapshotsHoldRegardlessOfCreationTime() {
        var tombstone = completedTombstone();
        OffsetDateTime afterDeletion = OffsetDateTime.parse("2026-09-08T12:00:00Z");

        var result = policy.assess(tombstone, List.of(
                new RecoveryPoint(RecoveryPointKind.MANUAL_SNAPSHOT, afterDeletion, afterDeletion),
                new RecoveryPoint(RecoveryPointKind.AWS_BACKUP_SNAPSHOT, afterDeletion, afterDeletion)));

        assertThat(result.mayRelease()).isFalse();
        assertThat(result.holdingRecoveryPointCount()).isEqualTo(2);
    }

    @Test
    void doesNotAllowReleasingAnInProgressDeletionOrUsingMissingInventory() {
        var pending = new AccountDeletionTombstone(UUID.randomUUID(), UUID.randomUUID(), "db-resource-1",
                List.of("v1_fingerprint"),
                AccountDeletionTombstone.State.IN_PROGRESS, COMPLETED, null, null, null, 0);

        assertThatThrownBy(() -> policy.assess(pending, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.assess(completedTombstone(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RecoveryPoint automated(String earliest, String latest) {
        return new RecoveryPoint(RecoveryPointKind.AUTOMATED_BACKUP,
                OffsetDateTime.parse(earliest), OffsetDateTime.parse(latest));
    }

    private static AccountDeletionTombstone completedTombstone() {
        return new AccountDeletionTombstone(UUID.randomUUID(), UUID.randomUUID(), "db-resource-1",
                List.of("v1_fingerprint"),
                AccountDeletionTombstone.State.COMPLETED, COMPLETED.minusDays(1), COMPLETED,
                null, null, 0);
    }
}
