package io.contractforge.backend.infrastructure.accountdeletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.contractforge.backend.application.accountdeletion.AccountDeletionRecoveryPointPolicy.RecoveryPointKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstanceAutomatedBackup;
import software.amazon.awssdk.services.rds.model.DBSnapshot;
import software.amazon.awssdk.services.rds.model.DescribeDbInstanceAutomatedBackupsRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstanceAutomatedBackupsResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbSnapshotsRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbSnapshotsResponse;
import software.amazon.awssdk.services.rds.model.RestoreWindow;

class RdsRecoveryPointInventoryTests {
    @Test
    void readsAwsRestoreWindowAndConservativelyIncludesManualAndAwsBackupSnapshots() {
        RdsClient rds = mock(RdsClient.class);
        var restoreWindow = RestoreWindow.builder().earliestTime(Instant.parse("2026-09-01T10:00:00Z"))
                .latestTime(Instant.parse("2026-09-01T12:00:00Z")).build();
        var automated = DBInstanceAutomatedBackup.builder().dbiResourceId("db-resource-1")
                .restoreWindow(restoreWindow).build();
        when(rds.describeDBInstanceAutomatedBackups(any(DescribeDbInstanceAutomatedBackupsRequest.class)))
                .thenReturn(DescribeDbInstanceAutomatedBackupsResponse.builder()
                        .dbInstanceAutomatedBackups(automated).build());
        when(rds.describeDBSnapshots(any(DescribeDbSnapshotsRequest.class))).thenAnswer(invocation -> {
            DescribeDbSnapshotsRequest request = invocation.getArgument(0);
            if ("automated".equals(request.snapshotType()) || "manual".equals(request.snapshotType())
                    || "awsbackup".equals(request.snapshotType())) {
                return DescribeDbSnapshotsResponse.builder().dbSnapshots(DBSnapshot.builder()
                        .dbiResourceId("db-resource-1").snapshotCreateTime(Instant.parse("2026-09-10T00:00:00Z"))
                        .build()).build();
            }
            return DescribeDbSnapshotsResponse.builder().build();
        });

        List<io.contractforge.backend.application.accountdeletion.AccountDeletionRecoveryPointPolicy.RecoveryPoint> points =
                new RdsRecoveryPointInventory(rds).list("db-resource-1");

        assertThat(points).extracting("kind").containsExactly(
                RecoveryPointKind.AUTOMATED_BACKUP,
                RecoveryPointKind.AUTOMATED_SNAPSHOT,
                RecoveryPointKind.MANUAL_SNAPSHOT,
                RecoveryPointKind.AWS_BACKUP_SNAPSHOT);
        assertThat(points.getFirst().earliestRestorableAt().toString()).isEqualTo("2026-09-01T10:00Z");
        assertThat(points.getFirst().latestRestorableAt().toString()).isEqualTo("2026-09-01T12:00Z");
    }

    @Test
    void marksInventoryIdentityOrMissingRestoreWindowAsUnknownInsteadOfReleasing() {
        RdsClient rds = mock(RdsClient.class);
        when(rds.describeDBInstanceAutomatedBackups(any(DescribeDbInstanceAutomatedBackupsRequest.class)))
                .thenReturn(DescribeDbInstanceAutomatedBackupsResponse.builder()
                        .dbInstanceAutomatedBackups(DBInstanceAutomatedBackup.builder()
                                .dbiResourceId("unexpected-resource").build(),
                                DBInstanceAutomatedBackup.builder().dbiResourceId("db-resource-1").build())
                        .build());
        when(rds.describeDBSnapshots(any(DescribeDbSnapshotsRequest.class)))
                .thenReturn(DescribeDbSnapshotsResponse.builder().build());

        var points = new RdsRecoveryPointInventory(rds).list("db-resource-1");

        assertThat(points).extracting("kind").containsExactly(
                RecoveryPointKind.AUTOMATED_BACKUP_IDENTITY_UNKNOWN,
                RecoveryPointKind.AUTOMATED_BACKUP);
        assertThat(points.get(1).earliestRestorableAt()).isNull();
    }
}
