package io.contractforge.backend.application.accountdeletion;

import io.contractforge.backend.domain.model.AccountDeletionJob;
import io.contractforge.backend.domain.model.AccountDeletionObjectKind;
import io.contractforge.backend.domain.model.AccountDeletionPhase;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable account-deletion state and private-object manifest. */
public interface AccountDeletionRepository {
    Optional<BeginResult> begin(UUID userId, UUID jobId, OffsetDateTime now, OffsetDateTime identityFenceUntil,
                                Duration uploadWriteGrace);

    Optional<UUID> findForUser(UUID userId);

    Optional<AccountDeletionJob> find(UUID jobId);

    List<ExternalIdentity> findIdentities(UUID userId);

    List<UUID> findReadyToDispatch(OffsetDateTime now, int limit);

    boolean claimDispatch(UUID jobId, UUID dispatchToken, OffsetDateTime now, OffsetDateTime leaseUntil);

    boolean markEnqueued(UUID jobId, UUID dispatchToken, OffsetDateTime now);

    void releaseDispatch(UUID jobId, UUID dispatchToken);

    Optional<AccountDeletionJob> claim(UUID jobId, UUID workerToken, OffsetDateTime now,
                                       OffsetDateTime leaseUntil);

    Optional<AccountDeletionJob> claimInitialDisable(UUID jobId, UUID workerToken, OffsetDateTime now,
                                                     OffsetDateTime leaseUntil);

    void reschedule(AccountDeletionJob job, OffsetDateTime availableAt, OffsetDateTime now);

    void advance(AccountDeletionJob job, AccountDeletionPhase nextPhase,
                 OffsetDateTime availableAt, OffsetDateTime now);

    List<OwnedUpload> findOwnedUploads(UUID userId);

    List<String> findOwnedTrustedObjectKeys(UUID userId);

    void captureObjects(UUID jobId, List<PendingObject> objects);

    Optional<PendingObject> findNextPendingObject(UUID jobId);

    void markObjectDeleted(AccountDeletionJob job, String objectKey, OffsetDateTime now);

    void purgeRelationalData(AccountDeletionJob job);

    void complete(AccountDeletionJob job, UUID systemUserId, OffsetDateTime now, String auditAction);

    record PendingObject(String objectKey, AccountDeletionObjectKind kind) { }

    record OwnedUpload(UUID id, String quarantineObjectKey) { }

    record ExternalIdentity(String provider, String providerSubject) { }

    record BeginResult(UUID deletionId, boolean created) { }
}
