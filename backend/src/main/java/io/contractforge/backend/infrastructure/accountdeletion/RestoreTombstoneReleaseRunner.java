package io.contractforge.backend.infrastructure.accountdeletion;

import io.contractforge.backend.application.accountdeletion.AccountDeletionRecoveryPointPolicy;
import io.contractforge.backend.application.accountdeletion.AccountDeletionTombstone;
import io.contractforge.backend.application.accountdeletion.AccountDeletionTombstonePort;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;

/** Recovery-point-gated tombstone release; no database connection is required. */
final class RestoreTombstoneReleaseRunner implements ApplicationRunner, ExitCodeGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(RestoreTombstoneReleaseRunner.class);

    private final AccountDeletionTombstonePort tombstones;
    private final RdsRecoveryPointInventory recoveryPoints;
    private final Clock clock;
    private final AccountDeletionRecoveryPointPolicy policy = new AccountDeletionRecoveryPointPolicy();
    private int exitCode;

    RestoreTombstoneReleaseRunner(AccountDeletionTombstonePort tombstones,
                                  RdsRecoveryPointInventory recoveryPoints, Clock clock) {
        this.tombstones = tombstones;
        this.recoveryPoints = recoveryPoints;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!"release".equals(requiredOption(arguments, "restore-reconcile"))) {
            throw new IllegalArgumentException("Tombstone release mode is required");
        }
        UUID operationId = UUID.fromString(requiredOption(arguments, "operation-id"));
        String sourceDbResourceId = requiredOption(arguments, "source-db-resource-id");
        AccountDeletionTombstone tombstone = tombstones.findAll().stream()
                .filter(item -> item.deletionId().equals(operationId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Account deletion tombstone was not found"));
        if (!tombstone.sourceDbResourceId().equals(sourceDbResourceId)) {
            throw new IllegalStateException("Recovery inventory source does not match the deletion ledger");
        }
        var assessment = policy.assess(tombstone, recoveryPoints.list(sourceDbResourceId));
        tombstones.recordRecoveryCheck(operationId, OffsetDateTime.now(clock),
                assessment.oldestRecoverablePointAt(), assessment.holdingRecoveryPointCount());
        if (!assessment.mayRelease()) {
            exitCode = 3;
            LOG.atWarn().addKeyValue("event_type", "account_deletion_tombstone_release_blocked")
                    .addKeyValue("deletion_id", operationId)
                    .addKeyValue("recoverable_point_count", assessment.holdingRecoveryPointCount())
                    .log("account_deletion_tombstone_release_blocked");
            return;
        }
        tombstones.releaseIfNoRecoverablePoints(operationId);
        LOG.atInfo().addKeyValue("event_type", "account_deletion_tombstone_released")
                .addKeyValue("deletion_id", operationId).log("account_deletion_tombstone_released");
    }

    private static String requiredOption(ApplicationArguments arguments, String name) {
        var values = arguments.getOptionValues(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("Restore reconciliation option is missing: " + name);
        }
        return values.getFirst();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
