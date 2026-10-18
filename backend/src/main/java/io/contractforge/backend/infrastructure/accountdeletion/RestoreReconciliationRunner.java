package io.contractforge.backend.infrastructure.accountdeletion;

import io.contractforge.backend.application.accountdeletion.AccountDeletionTombstone;
import io.contractforge.backend.application.accountdeletion.AccountDeletionTombstonePort;
import io.contractforge.backend.application.accountdeletion.AccountDeletionRepository;
import io.contractforge.backend.application.accountdeletion.AccountDeletionDisablePendingException;
import io.contractforge.backend.application.accountdeletion.AccountDeletionWorker;
import io.contractforge.backend.application.port.RepositoryPorts.UserRepository;
import java.time.Clock;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;

/** Fail-closed operator command for isolated restore reconciliation and recovery-based tombstone release. */
@Component
@Profile("restore-reconciliation")
final class RestoreReconciliationRunner implements ApplicationRunner, ExitCodeGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(RestoreReconciliationRunner.class);
    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final AccountDeletionTombstonePort tombstones;
    private final AccountDeletionRepository deletions;
    private final AccountDeletionWorker worker;
    private final RestoreReconciliationBootstrap bootstrap;
    private final UserRepository users;
    private final RdsClient rds;
    private final Clock clock;
    private final Environment environment;
    private int exitCode;

    RestoreReconciliationRunner(AccountDeletionTombstonePort tombstones, AccountDeletionRepository deletions,
                                AccountDeletionWorker worker, RestoreReconciliationBootstrap bootstrap,
                                UserRepository users, RdsClient rds,
                                Clock clock, Environment environment) {
        this.tombstones = tombstones;
        this.deletions = deletions;
        this.worker = worker;
        this.bootstrap = bootstrap;
        this.users = users;
        this.rds = rds;
        this.clock = clock;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!arguments.containsOption("restore-reconcile")) {
            throw new IllegalArgumentException("Restore reconciliation mode is required");
        }
        String mode = requiredOption(arguments, "restore-reconcile");
        try {
            switch (mode) {
                case "apply" -> reconcile(requireIsolatedTarget(arguments));
                default -> throw new IllegalArgumentException("Restore reconciliation mode is unsupported");
            }
        } catch (ReconciliationPendingException pending) {
            exitCode = 2;
            LOG.atWarn().addKeyValue("event_type", "restore_reconciliation_pending")
                    .addKeyValue("deletion_id", pending.deletionId()).log("restore_reconciliation_pending");
        }
    }

    private RestoredTarget requireIsolatedTarget(ApplicationArguments arguments) {
        if (!Boolean.parseBoolean(requiredOption(arguments, "confirm-isolated"))) {
            throw new IllegalArgumentException("Isolated database and disabled runtime traffic must be confirmed");
        }
        String sourceResourceId = requiredOption(arguments, "source-db-resource-id");
        String sourceIdentifier = requiredOption(arguments, "source-db-instance-identifier");
        String targetIdentifier = requiredOption(arguments, "target-db-instance-identifier");
        if (sourceResourceId.equals(targetIdentifier) || sourceIdentifier.equals(targetIdentifier)) {
            throw new IllegalArgumentException("Restored target must be a new isolated database instance");
        }
        var sources = rds.describeDBInstances(DescribeDbInstancesRequest.builder()
                .dbInstanceIdentifier(sourceIdentifier).build()).dbInstances();
        if (sources.size() != 1 || !sourceResourceId.equals(sources.getFirst().dbiResourceId())) {
            throw new IllegalStateException("Source database identifier does not match the approved resource ID");
        }
        var targets = rds.describeDBInstances(DescribeDbInstancesRequest.builder()
                .dbInstanceIdentifier(targetIdentifier).build()).dbInstances();
        if (targets.size() != 1) throw new IllegalStateException("Restored target database was not found uniquely");
        var target = targets.getFirst();
        if (!"available".equals(target.dbInstanceStatus()) || target.dbiResourceId() == null
                || target.dbiResourceId().equals(sourceResourceId)) {
            throw new IllegalStateException("Restored target database is not available and isolated");
        }
        String url = environment.getProperty("CONTRACTFORGE_DB_URL", "");
        String endpoint = target.endpoint() == null ? null : target.endpoint().address();
        if (endpoint == null || !usesEndpoint(url, endpoint)) {
            throw new IllegalStateException("Application database URL is not the isolated restored target");
        }
        return new RestoredTarget(sourceResourceId, targetIdentifier);
    }

    static boolean usesEndpoint(String jdbcUrl, String endpoint) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) return false;
        try {
            String databaseUriText = jdbcUrl.substring("jdbc:".length());
            if (databaseUriText.startsWith("aws-wrapper:")) {
                databaseUriText = databaseUriText.substring("aws-wrapper:".length());
            }
            if (!databaseUriText.startsWith("postgresql://")) return false;
            URI databaseUri = URI.create(databaseUriText);
            return endpoint.equalsIgnoreCase(databaseUri.getHost());
        } catch (IllegalArgumentException invalidUrl) {
            return false;
        }
    }

    private void reconcile(RestoredTarget target) {
        Map<UUID, AccountDeletionTombstone> byId = index(tombstones.findAll());
        Map<UUID, UUID> userOperations = new HashMap<>();
        Map<String, UUID> fingerprintOwners = new HashMap<>();
        byId.values().forEach(tombstone -> {
            if (!target.sourceResourceId().equals(tombstone.sourceDbResourceId())) {
                throw new IllegalStateException("Restore source database resource does not match its deletion ledger");
            }
            if (SYSTEM_USER_ID.equals(tombstone.userId())) {
                throw new IllegalStateException("System user cannot be an account-deletion tombstone target");
            }
            UUID previousOperation = userOperations.putIfAbsent(tombstone.userId(), tombstone.deletionId());
            if (previousOperation != null && !previousOperation.equals(tombstone.deletionId())) {
                throw new IllegalStateException("Multiple tombstones conflict for a restored user");
            }
            for (String fingerprint : tombstone.identityFingerprints()) {
                UUID prior = fingerprintOwners.putIfAbsent(fingerprint, tombstone.userId());
                if (prior != null && !prior.equals(tombstone.userId())) {
                    throw new IllegalStateException("External deletion tombstones contain a duplicate identity fingerprint");
                }
            }
        });

        for (AccountDeletionTombstone tombstone : byId.values()) {
            reconcileOne(tombstone);
            LOG.atInfo().addKeyValue("event_type", "restore_deletion_reconciled")
                    .addKeyValue("deletion_id", tombstone.deletionId()).log("restore_deletion_reconciled");
        }
        verifyNoRestoredTombstoneIdentities(byId.values());
        LOG.atInfo().addKeyValue("event_type", "restore_reconciliation_complete")
                .addKeyValue("tombstone_count", byId.size())
                .addKeyValue("source_db_resource_id", target.sourceResourceId())
                .addKeyValue("target_db_instance_identifier", target.targetIdentifier())
                .log("restore_reconciliation_complete");
    }

    private void reconcileOne(AccountDeletionTombstone tombstone) {
        var user = users.findById(tombstone.userId());
        var job = deletions.find(tombstone.deletionId());
        if (user.isEmpty()) {
            if (job.isPresent()) throw new IllegalStateException("Restored deletion job has no application account");
            if (!deletions.findIdentities(tombstone.userId()).isEmpty()) {
                throw new IllegalStateException("Restored identity mappings have no application account");
            }
            tombstones.complete(tombstone.deletionId(), tombstone.userId(), OffsetDateTime.now(clock));
            return;
        }
        if (job.isPresent() && !job.get().userId().equals(tombstone.userId())) {
            throw new IllegalStateException("Restored deletion job does not match its tombstone");
        }

        Set<String> expected = new HashSet<>(tombstone.identityFingerprints());
        Set<String> restored = new HashSet<>();
        for (AccountDeletionRepository.ExternalIdentity identity : deletions.findIdentities(tombstone.userId())) {
            restored.add(tombstones.fingerprint(identity.provider(), identity.providerSubject()));
            if (!tombstones.blocksProvisioning(identity.provider(), identity.providerSubject())) {
                throw new IllegalStateException("Restored provider identity is not blocked by its external tombstone");
            }
        }
        if (!restored.equals(expected)) {
            throw new IllegalStateException("Restored account identity mappings do not match the external tombstone");
        }

        UUID deletionId = bootstrap.ensureJob(tombstone)
                .orElseThrow(() -> new IllegalStateException("Restored account deletion could not be started"));
        while (deletions.find(deletionId).isPresent()) {
            var current = deletions.find(deletionId).orElseThrow();
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (current.availableAt().isAfter(now)) throw new ReconciliationPendingException(deletionId);
            if (current.phase() == io.contractforge.backend.domain.model.AccountDeletionPhase.WAIT_UPLOAD_CAPABILITIES
                    && now.isBefore(current.uploadCapabilitiesExpireAt())) {
                throw new ReconciliationPendingException(deletionId);
            }
            if (current.phase() == io.contractforge.backend.domain.model.AccountDeletionPhase.WAIT_IDENTITY_FENCE
                    && now.isBefore(current.identityFenceUntil())) {
                throw new ReconciliationPendingException(deletionId);
            }
            if (current.phase() == io.contractforge.backend.domain.model.AccountDeletionPhase.WAIT_IDENTITY_FENCE
                    && deletions.findNextPendingObject(deletionId).isPresent()) {
                throw new IllegalStateException("Completed object-cleanup phase retains a pending S3 manifest entry");
            }
            if (current.phase() == io.contractforge.backend.domain.model.AccountDeletionPhase.DISABLE_COGNITO) {
                try {
                    worker.disableCognitoImmediately(deletionId);
                } catch (AccountDeletionDisablePendingException pending) {
                    throw new ReconciliationPendingException(deletionId);
                }
            } else {
                worker.process(deletionId);
            }
            var next = deletions.find(deletionId);
            if (next.isPresent() && next.get().workerToken() != null) {
                throw new ReconciliationPendingException(deletionId);
            }
        }
        if (users.findById(tombstone.userId()).isPresent()
                || !deletions.findIdentities(tombstone.userId()).isEmpty()) {
            throw new IllegalStateException("Restored account remained after deletion reconciliation");
        }
        tombstones.complete(tombstone.deletionId(), tombstone.userId(), OffsetDateTime.now(clock));
    }

    private void verifyNoRestoredTombstoneIdentities(Iterable<AccountDeletionTombstone> values) {
        for (AccountDeletionTombstone tombstone : values) {
            if (users.findById(tombstone.userId()).isPresent()
                    || !deletions.findIdentities(tombstone.userId()).isEmpty()) {
                throw new IllegalStateException("Restored account identity remained after reconciliation");
            }
        }
    }

    private static Map<UUID, AccountDeletionTombstone> index(Iterable<AccountDeletionTombstone> tombstones) {
        Map<UUID, AccountDeletionTombstone> indexed = new java.util.TreeMap<>();
        for (AccountDeletionTombstone tombstone : tombstones) {
            if (indexed.putIfAbsent(tombstone.deletionId(), tombstone) != null) {
                throw new IllegalStateException("External account-deletion tombstone IDs are not unique");
            }
        }
        return indexed;
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

    private record RestoredTarget(String sourceResourceId, String targetIdentifier) { }

    private static final class ReconciliationPendingException extends RuntimeException {
        private final UUID deletionId;

        private ReconciliationPendingException(UUID deletionId) {
            super("Restore reconciliation remains pending for operation " + deletionId);
            this.deletionId = deletionId;
        }

        private UUID deletionId() {
            return deletionId;
        }
    }
}
