package io.contractforge.backend.application.accountdeletion;

import io.contractforge.backend.application.identity.ApplicationAccessDeniedException;
import io.contractforge.backend.application.port.RepositoryPorts.UserIdentityRepository;
import io.contractforge.backend.application.port.RepositoryPorts.UserRepository;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import io.contractforge.backend.domain.model.PersistenceModels.User;
import io.contractforge.backend.domain.model.PersistenceModels.UserIdentity;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Accepts account deletion without resolving or provisioning a new identity. */
@Service
@Profile("!test")
public class AccountDeletionService {
    private static final Logger LOG = LoggerFactory.getLogger(AccountDeletionService.class);
    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final AccountDeletionRepository deletions;
    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final AccountDeletionObjectManifest objectManifest;
    private final AccountDeletionQueuePort queue;
    private final AccountDeletionProperties properties;
    private final AccountDeletionQueueProperties queueProperties;
    private final AccountDeletionTombstonePort tombstones;
    private final Clock clock;

    public AccountDeletionService(AccountDeletionRepository deletions, UserRepository users,
                                  @Qualifier("userIdentityRepository") UserIdentityRepository identities,
                                  AccountDeletionObjectManifest objectManifest,
                                  AccountDeletionQueuePort queue, AccountDeletionProperties properties,
                                  AccountDeletionQueueProperties queueProperties,
                                  AccountDeletionTombstonePort tombstones, Clock clock) {
        this.deletions = deletions;
        this.users = users;
        this.identities = identities;
        this.objectManifest = objectManifest;
        this.queue = queue;
        this.properties = properties;
        this.queueProperties = queueProperties;
        this.tombstones = tombstones;
        this.clock = clock;
    }

    @Transactional
    public UUID request(AuthenticatedPrincipal principal) {
        if (!queue.configured()) throw new AccountDeletionUnavailableException();
        validate(principal);
        UserIdentity identity = identities.findByProviderSubject(principal.provider(), principal.providerSubject())
                .orElseThrow(ApplicationAccessDeniedException::new);
        User user = users.findById(identity.userId()).orElseThrow(ApplicationAccessDeniedException::new);
        if (SYSTEM_USER_ID.equals(user.id())) throw new ApplicationAccessDeniedException();

        OffsetDateTime now = OffsetDateTime.now(clock);
        AccountDeletionRepository.BeginResult begin = deletions.begin(user.id(), UUID.randomUUID(), now,
                now.plus(properties.identityFenceDuration()), properties.uploadWriteGrace())
                .orElseThrow(ApplicationAccessDeniedException::new);
        UUID jobId = begin.deletionId();
        if (begin.created()) registerFenceRollback(jobId);
        tombstones.recordInProgress(jobId, user.id(), deletions.findIdentities(user.id()), now);
        objectManifest.capture(jobId, user.id());
        if (begin.created() && !deletions.claimDispatch(jobId, UUID.randomUUID(), now,
                now.plus(queueProperties.dispatchLease()))) {
            throw new IllegalStateException("Account deletion dispatch reservation failed");
        }
        return jobId;
    }

    private void registerFenceRollback(UUID deletionId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_ROLLED_BACK) return;
                try {
                    tombstones.discardInProgress(deletionId);
                } catch (RuntimeException failure) {
                    LOG.atError().addKeyValue("event_type", "account_deletion_tombstone_rollback_cleanup_failed")
                            .addKeyValue("deletion_id", deletionId)
                            .log("account_deletion_tombstone_rollback_cleanup_failed");
                }
            }
        });
    }

    private void validate(AuthenticatedPrincipal principal) {
        if (principal == null || principal.provider() == null || principal.provider().isBlank()
                || principal.providerSubject() == null || principal.providerSubject().isBlank()) {
            throw new ApplicationAccessDeniedException();
        }
    }
}
