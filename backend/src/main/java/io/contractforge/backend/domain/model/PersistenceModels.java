package io.contractforge.backend.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import io.contractforge.backend.domain.model.UploadStatus;

/** Domain-facing persistence representations. They intentionally contain no JDBC types. */
public final class PersistenceModels {

    private PersistenceModels() {
    }

    public record JsonValue(String value) {
        public JsonValue {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("JSON value must not be blank");
            }
        }
    }

    public record User(UUID id, UserStatus status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public User(UUID id, String status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
            this(id, UserStatus.fromValue(status), createdAt, updatedAt);
        }
    }

    public record UserIdentity(UUID id, UUID userId, String provider, String providerSubject,
                               String email, OffsetDateTime createdAt) {
        public UserIdentity(UUID id, UUID userId, String provider, String providerSubject,
                            OffsetDateTime createdAt) {
            this(id, userId, provider, providerSubject, null, createdAt);
        }
    }

    public record Document(UUID id, UUID userId, String storageKey, String filename, String mimeType,
                           long size, ContractType contractType, DocumentStatus status,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public Document(UUID id, UUID userId, String storageKey, String filename, String mimeType,
                        long size, DocumentStatus status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
            this(id, userId, storageKey, filename, mimeType, size, ContractType.ELECTRICITY, status, createdAt, updatedAt);
        }

        public Document(UUID id, UUID userId, String storageKey, String filename, String mimeType,
                        long size, String status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
            this(id, userId, storageKey, filename, mimeType, size, ContractType.ELECTRICITY,
                    DocumentStatus.fromValue(status), createdAt, updatedAt);
        }

        public Document(UUID id, UUID userId, String storageKey, String filename, String mimeType,
                        long size, ContractType contractType, String status,
                        OffsetDateTime createdAt, OffsetDateTime updatedAt) {
            this(id, userId, storageKey, filename, mimeType, size, contractType,
                    DocumentStatus.fromValue(status), createdAt, updatedAt);
        }
    }

    public record Contract(UUID documentId, ContractType type, ContractStatus status, JsonValue data,
                           long version, OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime archivedAt) {
        public Contract(UUID documentId, String type, String status, JsonValue data,
                        long version, OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime archivedAt) {
            this(documentId, ContractType.fromValue(type), ContractStatus.fromValue(status), data, version,
                    createdAt, updatedAt, archivedAt);
        }

        public Contract(UUID documentId, ContractType type, String status, JsonValue data,
                        long version, OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime archivedAt) {
            this(documentId, type, ContractStatus.fromValue(status), data, version, createdAt, updatedAt, archivedAt);
        }

        public Contract(UUID documentId, ContractType type, String status, JsonValue data,
                        OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime archivedAt) {
            this(documentId, type, ContractStatus.fromValue(status), data, 0L, createdAt, updatedAt, archivedAt);
        }

        public Contract(UUID documentId, String type, String status, JsonValue data,
                        OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime archivedAt) {
            this(documentId, ContractType.fromValue(type), ContractStatus.fromValue(status), data, 0L,
                    createdAt, updatedAt, archivedAt);
        }
    }

    public record ContractParty(UUID id, UUID contractId, ContractPartyRole role, String name) {
        public ContractParty(UUID id, UUID contractId, String role, String name) {
            this(id, contractId, ContractPartyRole.fromValue(role), name);
        }
    }

    public record ContractTerms(UUID contractId, LocalDate startDate, LocalDate endDate,
                                Integer contractDuration, ContractRenewalType renewalType, ContractPricingModel pricingModel,
                                JsonValue electricityData) {
        public ContractTerms(UUID contractId, LocalDate startDate, LocalDate endDate, Integer contractDuration,
                             String renewalType, String pricingModel, JsonValue electricityData) {
            this(contractId, startDate, endDate, contractDuration,
                    renewalType == null ? null : ContractRenewalType.fromValue(renewalType),
                    pricingModel == null ? null : ContractPricingModel.fromValue(pricingModel), electricityData);
        }
    }

    public record ContractAggregate(Contract contract, List<ContractParty> parties,
                                   ContractTerms terms) {
        public ContractAggregate {
            parties = List.copyOf(parties);
        }
    }

    /** Trusted ownership context and approved contract facts supplied to monitoring. */
    public record MonitoringContract(UUID userId, ContractAggregate aggregate) {
        public MonitoringContract {
            if (userId == null || aggregate == null || aggregate.contract() == null) {
                throw new IllegalArgumentException("Monitoring contract context is required");
            }
            if (aggregate.contract().documentId() == null) {
                throw new IllegalArgumentException("Monitoring contract must have a document");
            }
            if (aggregate.contract().status() != ContractStatus.ACTIVE) {
                throw new IllegalArgumentException("Only ACTIVE contracts can be monitored");
            }
            if (aggregate.contract().type() != ContractType.ELECTRICITY) {
                throw new IllegalArgumentException("Only electricity contracts can be monitored");
            }
        }

        public UUID documentId() {
            return aggregate.contract().documentId();
        }
    }

    public record Event(UUID id, UUID userId, UUID documentId, MonitoringEventType type, String description,
                        OffsetDateTime eventDate, BigDecimal amount, String currency, EventStatus status,
                        String sourceReference, OffsetDateTime createdAt) {
        public Event(UUID id, UUID userId, UUID documentId, String type, String description,
                     OffsetDateTime eventDate, BigDecimal amount, String currency, String status,
                     String sourceReference, OffsetDateTime createdAt) {
            this(id, userId, documentId, MonitoringEventType.fromValue(type), description, eventDate, amount,
                    currency, EventStatus.fromValue(status), sourceReference, createdAt);
        }
    }

    public record Recommendation(UUID id, UUID userId, UUID documentId, RecommendationType type, JsonValue data,
                                 OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        public Recommendation(UUID id, UUID userId, UUID documentId, String type, JsonValue data,
                               OffsetDateTime createdAt, OffsetDateTime expiresAt) {
            this(id, userId, documentId, RecommendationType.fromValue(type), data, createdAt, expiresAt);
        }
    }

    public record Notification(UUID id, UUID recommendationId, NotificationType notificationType, JsonValue data,
                               OffsetDateTime createdAt) {
        public Notification(UUID id, UUID recommendationId, String notificationType, JsonValue data,
                            OffsetDateTime createdAt) {
            this(id, recommendationId, NotificationType.fromValue(notificationType), data, createdAt);
        }
    }

    public record NotificationDeliveryWork(NotificationDelivery delivery, Notification notification) {
        public NotificationDeliveryWork {
            if (delivery == null || notification == null
                    || !delivery.notificationId().equals(notification.id())) {
                throw new IllegalArgumentException("Notification delivery relationship is incomplete");
            }
        }
    }

    public record NotificationDelivery(UUID id, UUID notificationId, NotificationChannel channel,
                                       NotificationDeliveryStatus status,
                                       String destination, int attemptCount, OffsetDateTime inFlightAt,
                                       OffsetDateTime leaseUntil, UUID claimToken, OffsetDateTime lastAttemptAt,
                                       OffsetDateTime deliveredAt, OffsetDateTime lastFailureAt, String failureCode,
                                       OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public NotificationDelivery {
            if (id == null || notificationId == null || channel == null || status == null || attemptCount < 0
                    || createdAt == null || updatedAt == null) {
                throw new IllegalArgumentException("Notification delivery state is incomplete");
            }
        }

    }

    public record ProcessingAttempt(UUID id, UUID documentId, int attemptNumber, ProcessingAttemptStatus status,
                                   OffsetDateTime startedAt, OffsetDateTime completedAt, String errorCode) {
        public ProcessingAttempt(UUID id, UUID documentId, int attemptNumber, String status,
                                 OffsetDateTime startedAt, OffsetDateTime completedAt, String errorCode) {
            this(id, documentId, attemptNumber, ProcessingAttemptStatus.fromValue(status), startedAt, completedAt, errorCode);
        }
    }

    public record Upload(UUID id, UUID userId, UploadStatus status, String objectKey,
                         String originalFilename, ContractType contractType,
                         OffsetDateTime expiresAt, OffsetDateTime createdAt) {
        public Upload(UUID id, UUID userId, UploadStatus status, String objectKey,
                      String originalFilename, OffsetDateTime expiresAt, OffsetDateTime createdAt) {
            this(id, userId, status, objectKey, originalFilename, ContractType.ELECTRICITY, expiresAt, createdAt);
        }
    }

    public record AuditEvent(UUID id, UUID userId, String resourceType, UUID resourceId,
                             String action, OffsetDateTime createdAt) {
    }

    public record UsageRecord(UUID id, UUID userId, UUID documentId, String operation,
                              String provider, String service, JsonValue usageData,
                              BigDecimal costAmount, String currency, UUID usageEventId,
                              OffsetDateTime createdAt, UsageCostStatus costStatus,
                              String pricingVersion) {
        public UsageRecord(UUID id, UUID userId, UUID documentId, String operation,
                           String provider, String service, JsonValue usageData,
                           BigDecimal costAmount, String currency, UUID usageEventId,
                           OffsetDateTime createdAt) {
            this(id, userId, documentId, operation, provider, service, usageData, costAmount,
                    currency, usageEventId, createdAt, UsageCostStatus.LEGACY, null);
        }
    }
}
