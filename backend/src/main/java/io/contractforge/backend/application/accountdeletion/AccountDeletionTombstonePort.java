package io.contractforge.backend.application.accountdeletion;

import io.contractforge.backend.application.accountdeletion.AccountDeletionRepository.ExternalIdentity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** External, non-restored ledger for account-deletion identity fences and recovery release metadata. */
public interface AccountDeletionTombstonePort {
    String fingerprint(String provider, String providerSubject);

    void recordInProgress(UUID deletionId, UUID userId, List<ExternalIdentity> identities, OffsetDateTime fencedAt);

    boolean blocksProvisioning(String provider, String providerSubject);

    Optional<AccountDeletionTombstone> find(UUID deletionId);

    void complete(UUID deletionId, UUID userId, OffsetDateTime completedAt);

    List<AccountDeletionTombstone> findAll();

    void recordRecoveryCheck(UUID deletionId, OffsetDateTime checkedAt,
                             Optional<OffsetDateTime> oldestRecoverablePointAt, int recoverablePointCount);

    void releaseIfNoRecoverablePoints(UUID deletionId);

    /** Compensates only an uncommitted fence after its relational acceptance transaction rolls back. */
    void discardInProgress(UUID deletionId);
}
