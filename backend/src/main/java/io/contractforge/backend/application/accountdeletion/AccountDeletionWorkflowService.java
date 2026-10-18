package io.contractforge.backend.application.accountdeletion;

import io.contractforge.backend.domain.model.AccountDeletionAuditAction;
import io.contractforge.backend.domain.model.AccountDeletionPhase;
import io.contractforge.backend.domain.model.AccountDeletionJob;
import io.contractforge.backend.application.port.RepositoryPorts.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Transactional steps keep object manifests and relational purge recoverable across S3/Cognito calls. */
@Service
@Profile("!test")
public class AccountDeletionWorkflowService {
    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final AccountDeletionRepository deletions;
    private final UserRepository users;
    private final AccountDeletionTombstonePort tombstones;
    private final AccountDeletionObjectManifest objectManifest;
    private final Clock clock;

    public AccountDeletionWorkflowService(AccountDeletionRepository deletions, UserRepository users,
                                          AccountDeletionTombstonePort tombstones,
                                          AccountDeletionObjectManifest objectManifest, Clock clock) {
        this.deletions = deletions;
        this.users = users;
        this.tombstones = tombstones;
        this.objectManifest = objectManifest;
        this.clock = clock;
    }

    @Transactional
    public void captureAndPurge(AccountDeletionJob job) {
        objectManifest.capture(job.id(), job.userId());
        OffsetDateTime now = OffsetDateTime.now(clock);
        deletions.purgeRelationalData(job);
        deletions.advance(job, AccountDeletionPhase.DELETE_OBJECTS, now, now);
    }

    @Transactional
    public void complete(AccountDeletionJob job) {
        deletions.complete(job, SYSTEM_USER_ID, OffsetDateTime.now(clock),
                AccountDeletionAuditAction.COMPLETED.name());
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Account deletion completion requires a transaction commit boundary");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                tombstones.complete(job.id(), job.userId(), OffsetDateTime.now(clock));
            }
        });
    }

    /** Retries an external tombstone completion after the relational deletion transaction has committed. */
    @Transactional(readOnly = true)
    public void completeTombstoneIfApplicationDataIsPurged(UUID deletionId) {
        AccountDeletionTombstone tombstone = tombstones.find(deletionId).orElse(null);
        if (tombstone == null || tombstone.state() == AccountDeletionTombstone.State.COMPLETED) return;
        if (deletions.find(deletionId).isPresent() || users.findById(tombstone.userId()).isPresent()
                || !deletions.findIdentities(tombstone.userId()).isEmpty()) {
            return;
        }
        tombstones.complete(tombstone.deletionId(), tombstone.userId(), OffsetDateTime.now(clock));
    }
}
