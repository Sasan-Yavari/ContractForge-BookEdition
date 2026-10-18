package io.contractforge.backend.application.accountdeletion;

import io.contractforge.backend.application.port.ObjectStoragePort;
import io.contractforge.backend.application.port.UploadObjectStoragePort;
import io.contractforge.backend.domain.model.AccountDeletionJob;
import io.contractforge.backend.domain.model.AccountDeletionObjectKind;
import io.contractforge.backend.domain.model.AccountDeletionPhase;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Advances one idempotent stage per queue delivery; failed external steps remain retryable. */
@Service
@Profile("!test")
public final class AccountDeletionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AccountDeletionWorker.class);

    private final AccountDeletionRepository deletions;
    private final AccountDeletionWorkflowService workflow;
    private final CognitoAccountAdministrationPort cognito;
    private final ObjectStoragePort trustedStorage;
    private final UploadObjectStoragePort uploadStorage;
    private final AccountDeletionQueueProperties queueProperties;
    private final Clock clock;

    public AccountDeletionWorker(AccountDeletionRepository deletions, AccountDeletionWorkflowService workflow,
                                 CognitoAccountAdministrationPort cognito, ObjectStoragePort trustedStorage,
                                 UploadObjectStoragePort uploadStorage, AccountDeletionQueueProperties queueProperties,
                                 Clock clock) {
        this.deletions = deletions;
        this.workflow = workflow;
        this.cognito = cognito;
        this.trustedStorage = trustedStorage;
        this.uploadStorage = uploadStorage;
        this.queueProperties = queueProperties;
        this.clock = clock;
    }

    public void process(UUID deletionId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        AccountDeletionJob job = claim(deletionId, now);
        if (job == null) {
            workflow.completeTombstoneIfApplicationDataIsPurged(deletionId);
            return;
        }

        switch (job.phase()) {
            case DISABLE_COGNITO -> disable(job);
            case WAIT_UPLOAD_CAPABILITIES -> waitForUploadCapabilities(job, now);
            case PURGE_RELATIONAL -> workflow.captureAndPurge(job);
            case DELETE_OBJECTS -> deleteObject(job, now);
            case DELETE_COGNITO -> deleteCognito(job, now);
            case WAIT_IDENTITY_FENCE -> waitForIdentityFence(job, now);
        }
    }

    /** Called by the authenticated request path so Cognito is disabled before its 202 response. */
    public void disableCognitoImmediately(UUID deletionId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        AccountDeletionJob job = deletions.claimInitialDisable(deletionId, UUID.randomUUID(), now,
                now.plus(queueProperties.workerLease())).orElse(null);
        if (job == null) {
            AccountDeletionJob current = deletions.find(deletionId).orElse(null);
            if (current != null && current.phase() != AccountDeletionPhase.DISABLE_COGNITO) return;
            throw new AccountDeletionDisablePendingException(queueProperties.pollSeconds());
        }
        try {
            disable(job);
        } catch (RuntimeException failure) {
            deletions.reschedule(job, OffsetDateTime.now(clock), OffsetDateTime.now(clock));
            LOG.atWarn()
                    .addKeyValue("event_type", "account_deletion_cognito_disable_pending")
                    .addKeyValue("deletion_id", job.id())
                    .log("account_deletion_cognito_disable_pending");
            throw new AccountDeletionDisablePendingException(queueProperties.pollSeconds());
        }
    }

    private AccountDeletionJob claim(UUID deletionId, OffsetDateTime now) {
        return deletions.claim(deletionId, UUID.randomUUID(), now,
                now.plus(queueProperties.workerLease())).orElse(null);
    }

    private void disable(AccountDeletionJob job) {
        for (AccountDeletionRepository.ExternalIdentity identity : findIdentities(job)) {
            cognito.disable(identity.provider(), identity.providerSubject());
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        deletions.advance(job, AccountDeletionPhase.WAIT_UPLOAD_CAPABILITIES,
                job.uploadCapabilitiesExpireAt(), now);
    }

    private void waitForUploadCapabilities(AccountDeletionJob job, OffsetDateTime now) {
        if (now.isBefore(job.uploadCapabilitiesExpireAt())) {
            deletions.reschedule(job, job.uploadCapabilitiesExpireAt(), now);
            return;
        }
        deletions.advance(job, AccountDeletionPhase.PURGE_RELATIONAL, now, now);
    }

    private void deleteObject(AccountDeletionJob job, OffsetDateTime now) {
        AccountDeletionRepository.PendingObject object = deletions.findNextPendingObject(job.id()).orElse(null);
        if (object == null) {
            deletions.advance(job, AccountDeletionPhase.DELETE_COGNITO, now, now);
            return;
        }
        if (object.kind() == AccountDeletionObjectKind.QUARANTINE) {
            uploadStorage.deleteQuarantineObject(object.objectKey());
        } else {
            trustedStorage.deletePrivateObject(object.objectKey());
        }
        deletions.markObjectDeleted(job, object.objectKey(), OffsetDateTime.now(clock));
    }

    private void deleteCognito(AccountDeletionJob job, OffsetDateTime now) {
        for (AccountDeletionRepository.ExternalIdentity identity : findIdentities(job)) {
            cognito.delete(identity.provider(), identity.providerSubject());
        }
        deletions.advance(job, AccountDeletionPhase.WAIT_IDENTITY_FENCE, job.identityFenceUntil(),
                OffsetDateTime.now(clock));
    }

    private List<AccountDeletionRepository.ExternalIdentity> findIdentities(AccountDeletionJob job) {
        List<AccountDeletionRepository.ExternalIdentity> identities = deletions.findIdentities(job.userId());
        if (identities.isEmpty()) {
            throw new IllegalStateException("Account deletion identity mappings are missing");
        }
        return identities;
    }

    private void waitForIdentityFence(AccountDeletionJob job, OffsetDateTime now) {
        if (now.isBefore(job.identityFenceUntil())) {
            deletions.reschedule(job, job.identityFenceUntil(), now);
            return;
        }
        workflow.complete(job);
    }
}
