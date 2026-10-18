package io.contractforge.backend.infrastructure.accountdeletion;

import io.contractforge.backend.application.accountdeletion.AccountDeletionRecoveryPointPolicy.RecoveryPoint;
import io.contractforge.backend.application.accountdeletion.AccountDeletionRecoveryPointPolicy.RecoveryPointKind;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstanceAutomatedBackup;
import software.amazon.awssdk.services.rds.model.DBSnapshot;
import software.amazon.awssdk.services.rds.model.DescribeDbInstanceAutomatedBackupsRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbSnapshotsRequest;
import software.amazon.awssdk.services.rds.model.Filter;

/** Inventories AWS-reported restore windows and snapshots for the source DB resource in this Region. */
@Component
@Profile("restore-reconciliation")
final class RdsRecoveryPointInventory {
    private static final List<String> SNAPSHOT_TYPES = List.of("automated", "manual", "awsbackup");

    private final RdsClient rds;

    RdsRecoveryPointInventory(RdsClient rds) {
        this.rds = rds;
    }

    List<RecoveryPoint> list(String sourceDbResourceId) {
        if (sourceDbResourceId == null || sourceDbResourceId.isBlank()) {
            throw new IllegalArgumentException("Source DB resource ID is required");
        }
        List<RecoveryPoint> points = new ArrayList<>();
        readAutomatedBackups(sourceDbResourceId, points);
        for (String snapshotType : SNAPSHOT_TYPES) readSnapshots(sourceDbResourceId, snapshotType, points);
        return List.copyOf(points);
    }

    private void readAutomatedBackups(String sourceDbResourceId, List<RecoveryPoint> points) {
        String marker = null;
        do {
            var request = DescribeDbInstanceAutomatedBackupsRequest.builder()
                    .filters(Filter.builder().name("dbi-resource-id").values(sourceDbResourceId).build())
                    .marker(marker).build();
            var response = rds.describeDBInstanceAutomatedBackups(request);
            for (DBInstanceAutomatedBackup backup : response.dbInstanceAutomatedBackups()) {
                if (!sourceDbResourceId.equals(backup.dbiResourceId())) {
                    points.add(new RecoveryPoint(RecoveryPointKind.AUTOMATED_BACKUP_IDENTITY_UNKNOWN, null, null));
                    continue;
                }
                var window = backup.restoreWindow();
                OffsetDateTime earliest = window == null ? null : atUtc(window.earliestTime());
                OffsetDateTime latest = window == null ? null : atUtc(window.latestTime());
                points.add(new RecoveryPoint(RecoveryPointKind.AUTOMATED_BACKUP, earliest, latest));
                if (backup.dbInstanceAutomatedBackupsReplications() != null
                        && !backup.dbInstanceAutomatedBackupsReplications().isEmpty()) {
                    points.add(new RecoveryPoint(RecoveryPointKind.REPLICATED_AUTOMATED_BACKUP, null, null));
                }
            }
            marker = response.marker();
        } while (marker != null && !marker.isBlank());
    }

    private void readSnapshots(String sourceDbResourceId, String snapshotType, List<RecoveryPoint> points) {
        String marker = null;
        do {
            var request = DescribeDbSnapshotsRequest.builder().snapshotType(snapshotType)
                    .filters(Filter.builder().name("dbi-resource-id").values(sourceDbResourceId).build())
                    .marker(marker).build();
            var response = rds.describeDBSnapshots(request);
            for (DBSnapshot snapshot : response.dbSnapshots()) {
                if (!sourceDbResourceId.equals(snapshot.dbiResourceId())) {
                    points.add(new RecoveryPoint(RecoveryPointKind.SNAPSHOT_IDENTITY_UNKNOWN, null, null));
                    continue;
                }
                OffsetDateTime createdAt = atUtc(snapshot.snapshotCreateTime());
                RecoveryPointKind kind = switch (snapshotType) {
                    case "automated" -> RecoveryPointKind.AUTOMATED_SNAPSHOT;
                    case "manual" -> RecoveryPointKind.MANUAL_SNAPSHOT;
                    case "awsbackup" -> RecoveryPointKind.AWS_BACKUP_SNAPSHOT;
                    default -> throw new IllegalStateException("Unsupported RDS snapshot type");
                };
                points.add(new RecoveryPoint(kind, createdAt, createdAt));
            }
            marker = response.marker();
        } while (marker != null && !marker.isBlank());
    }

    private static OffsetDateTime atUtc(java.time.Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
