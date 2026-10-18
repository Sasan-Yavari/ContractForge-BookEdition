package io.contractforge.backend.application.port;

import io.contractforge.backend.application.contract.ContractCollectionEntry;
import io.contractforge.backend.application.identity.ProviderSubjectAlreadyExistsException;
import io.contractforge.backend.domain.model.PersistenceModels;
import io.contractforge.backend.domain.model.ProcessingAttemptStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Domain-oriented repository ports; transaction ownership remains with application services. */
public final class RepositoryPorts {

    private RepositoryPorts() {
    }

    public interface UserRepository {
        Optional<PersistenceModels.User> findById(UUID id);

        void save(PersistenceModels.User user);
    }

    public interface UserIdentityRepository {
        Optional<PersistenceModels.UserIdentity> findByProviderSubject(String provider, String providerSubject);

        Optional<String> findTrustedEmailByUserId(UUID userId);

        void updateTrustedEmail(UUID identityId, String email);

        /**
         * @throws ProviderSubjectAlreadyExistsException only for the provider-subject unique constraint
         */
        void save(PersistenceModels.UserIdentity identity);
    }

    public interface DocumentRepository {
        Optional<PersistenceModels.Document> findById(UUID id);

        Optional<PersistenceModels.Document> findByIdForUpdate(UUID id);

        void save(PersistenceModels.Document document);

        void markProcessingSucceeded(UUID id);

        int markProcessingFailed(UUID id);

        void deleteById(UUID id);

        /** Deletes only when the supplied user owns the document and returns its private storage key. */
        default Optional<String> deleteOwnedById(UUID id, UUID userId) {
            return findById(id).filter(document -> userId.equals(document.userId())).map(document -> {
                deleteById(id);
                return document.storageKey();
            });
        }
    }

    public interface ContractRepository {
        Optional<PersistenceModels.ContractAggregate> findByDocumentId(UUID documentId);

        List<PersistenceModels.MonitoringContract> findEligibleForMonitoring();

        void save(PersistenceModels.ContractAggregate aggregate);

        int updateDraft(PersistenceModels.ContractAggregate aggregate, long expectedVersion);

        int updateActive(PersistenceModels.ContractAggregate aggregate, long expectedVersion);

        int approveDraft(UUID documentId, long expectedVersion);
    }

    /** Purpose-built read model for the authenticated user's complete contract collection. */
    public interface ContractCollectionRepository {
        List<ContractCollectionEntry> findByUserId(UUID userId, OffsetDateTime evaluatedAt);
    }

    public interface EventRepository {
        void save(PersistenceModels.Event event);

        int saveIfAbsent(PersistenceModels.Event event);

        int cancelCurrentContractEvents(UUID documentId);

        List<PersistenceModels.Event> findByUserId(UUID userId);

        List<PersistenceModels.Event> findByUserIdAndDocumentId(UUID userId, UUID documentId);

        Optional<PersistenceModels.Event> findById(UUID id);
    }

    public interface RecommendationRepository {
        void save(PersistenceModels.Recommendation recommendation);

        int saveIfAbsent(PersistenceModels.Recommendation recommendation);

        int expireCurrentContractRecommendations(UUID documentId, OffsetDateTime expiredAt);

        List<PersistenceModels.Recommendation> findByUserId(UUID userId, boolean includeHistory,
                                                            OffsetDateTime evaluatedAt);

        List<PersistenceModels.Recommendation> findByUserIdAndDocumentId(UUID userId, UUID documentId,
                                                                          boolean includeHistory,
                                                                          OffsetDateTime evaluatedAt);

        Optional<PersistenceModels.Recommendation> findById(UUID id);
    }

    public interface NotificationRepository {
        int saveIfAbsent(PersistenceModels.Notification notification);

        Optional<PersistenceModels.Notification> findByRecommendationId(UUID recommendationId);

    }

    public interface NotificationDeliveryRepository {
        int saveIfAbsent(PersistenceModels.NotificationDelivery delivery);

        Optional<PersistenceModels.NotificationDelivery> findById(UUID id);

        List<PersistenceModels.NotificationDelivery> findQueued();

        Optional<PersistenceModels.NotificationDeliveryWork> claim(UUID deliveryId, UUID notificationId,
                                                                    java.time.OffsetDateTime claimedAt,
                                                                    java.time.OffsetDateTime leaseUntil,
                                                                    UUID claimToken);

        Optional<PersistenceModels.NotificationDeliveryWork> findClaimed(UUID deliveryId, UUID claimToken,
                                                                          java.time.OffsetDateTime now);

        int recoverExpiredClaims(java.time.OffsetDateTime now);

        int markDelivered(UUID deliveryId, UUID claimToken, java.time.OffsetDateTime deliveredAt);

        int returnToQueued(UUID deliveryId, UUID claimToken, java.time.OffsetDateTime failedAt,
                           String failureCode);

        int markFailed(UUID deliveryId, UUID claimToken, java.time.OffsetDateTime failedAt,
                       String failureCode);
    }

    public interface ProcessingAttemptRepository {
        void save(PersistenceModels.ProcessingAttempt attempt);

        Optional<PersistenceModels.ProcessingAttempt> findById(UUID id);

        Optional<PersistenceModels.ProcessingAttempt> findLatestByDocumentId(UUID documentId);

        Optional<PersistenceModels.ProcessingAttempt> findRunningByDocumentId(UUID documentId);

        int finalize(UUID id, ProcessingAttemptStatus status, String errorCode);

        int finalizeRunningByDocumentId(UUID documentId, String errorCode);
    }

    public interface UploadRepository {
        void save(PersistenceModels.Upload upload);

        Optional<PersistenceModels.Upload> findById(UUID id);

        Optional<PersistenceModels.Upload> findByIdForUpdate(UUID id);

        int markUploaded(UUID id, String expectedObjectKey);

        int markScanned(UUID id);

        int markPromoted(UUID id);

        int markCompleted(UUID id);

        int markRejected(UUID id, String expectedObjectKey);
    }

    public interface AuditEventRepository {
        void append(PersistenceModels.AuditEvent event);
    }

    public interface UsageRecordRepository {
        void save(PersistenceModels.UsageRecord record);

        int saveIfAbsent(PersistenceModels.UsageRecord record);

        Optional<PersistenceModels.UsageRecord> findByUsageEventId(UUID usageEventId);
    }
}
