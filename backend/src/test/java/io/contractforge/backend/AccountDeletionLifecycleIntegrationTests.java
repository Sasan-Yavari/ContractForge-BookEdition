package io.contractforge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.contractforge.backend.application.accountdeletion.AccountDeletionRepository;
import io.contractforge.backend.application.accountdeletion.AccountDeletionService;
import io.contractforge.backend.application.accountdeletion.AccountDeletionWorker;
import io.contractforge.backend.application.accounting.AccountingPort;
import io.contractforge.backend.application.accounting.AccountingWorker;
import io.contractforge.backend.application.identity.ApplicationAccessDeniedException;
import io.contractforge.backend.application.identity.IdentityResolutionService;
import io.contractforge.backend.application.port.ObjectStoragePort;
import io.contractforge.backend.application.port.ProcessingPublisherPort;
import io.contractforge.backend.application.port.UploadObjectStoragePort;
import io.contractforge.backend.application.processing.ProcessingOrchestratorService;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import io.contractforge.backend.application.upload.UploadIngestionService;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import io.contractforge.backend.domain.model.UsageCostStatus;
import io.contractforge.backend.application.accountdeletion.CognitoAccountAdministrationPort;
import io.contractforge.backend.application.accountdeletion.AccountDeletionQueuePort;
import io.contractforge.backend.application.document.DocumentDeletionQueuePort;
import io.contractforge.backend.application.document.DocumentDeletionService;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import java.math.BigDecimal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@Import(AccountDeletionLifecycleIntegrationTests.DeletionTestPorts.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountDeletionLifecycleIntegrationTests extends PostgresIntegrationTest {
    private static final UUID SYSTEM_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID userId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID uploadId = documentId;
    private final UUID eventId = UUID.randomUUID();
    private final UUID recommendationId = UUID.randomUUID();
    private final UUID notificationId = UUID.randomUUID();
    private final UUID deliveryId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();
    private final UUID usageId = UUID.randomUUID();
    private final UUID ownerAuditId = UUID.randomUUID();
    private String subject;
    private String secondSubject;
    private UUID deletionId;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountDeletionService deletionService;
    @Autowired private DocumentDeletionService documentDeletionService;
    @Autowired private AccountDeletionRepository deletions;
    @Autowired private AccountDeletionWorker deletionWorker;
    @Autowired private IdentityResolutionService identityResolution;
    @Autowired private ProcessingOrchestratorService processing;
    @Autowired private AccountingWorker accounting;
    @Autowired private UploadIngestionService ingestion;
    @Autowired private TestCognito cognito;
    @Autowired private TestStorage storage;
    @Autowired private TestUploadStorage uploadStorage;

    @AfterEach
    void clean() {
        if (deletionId != null) {
            jdbc.update("DELETE FROM account_deletion_jobs WHERE id = ?", deletionId);
            jdbc.update("DELETE FROM audit_events WHERE resource_id = ?", deletionId);
        }
        jdbc.update("DELETE FROM audit_events WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        if (subject != null) jdbc.update("DELETE FROM user_identities WHERE provider_subject IN (?, ?)", subject,
                secondSubject);
    }

    @Test
    void fencesAndErasesAllOwnedDataRetriesObjectFailureAndRejectsLateWork() {
        OffsetDateTime now = OffsetDateTime.now();
        subject = "issue163-" + UUID.randomUUID();
        secondSubject = "issue163-secondary-" + UUID.randomUUID();
        String quarantineKey = "quarantine/incoming/" + uploadId;
        String trustedKey = "private/trusted/documents/" + uploadId;
        insertAccountData(now, quarantineKey, trustedKey);

        assertThatThrownBy(() -> documentDeletionService.delete(new AuthenticatedApplicationUser(userId), documentId))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE id = ?", Integer.class, documentId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uploads WHERE id = ?", Integer.class, uploadId)).isEqualTo(1);

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal("cognito", subject, "owner@example.test", true);
        deletionId = deletionService.request(principal);
        jdbc.update("UPDATE account_deletion_jobs SET upload_capabilities_expire_at = CURRENT_TIMESTAMP, "
                + "available_at = CURRENT_TIMESTAMP WHERE id = ?", deletionId);
        assertThat(jdbc.queryForObject("SELECT dispatch_token FROM account_deletion_jobs WHERE id = ?",
                UUID.class, deletionId)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_identities WHERE user_id = ? AND email IS NULL",
                Integer.class, userId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_deletion_objects WHERE deletion_id = ?", Integer.class,
                deletionId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_deletion_objects WHERE deletion_id = ? "
                + "AND object_key = ? AND object_kind = 'TRUSTED'", Integer.class, deletionId, trustedKey)).isEqualTo(1);
        assertThat(deletions.findOwnedUploads(userId)).hasSize(1);
        assertThatThrownBy(() -> identityResolution.resolve(principal)).isInstanceOf(ApplicationAccessDeniedException.class);
        assertThatThrownBy(() -> identityResolution.resolve(
                new AuthenticatedPrincipal("cognito", secondSubject, "owner@example.test", true)))
                .isInstanceOf(ApplicationAccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId)).isEqualTo(1);

        AccountingPort.UsageEvent lateUsage = new AccountingPort.UsageEvent(UUID.randomUUID(), userId, documentId,
                "TEXTRACT", "AWS", "TEXTRACT", new JsonValue("{}"), BigDecimal.ONE, "USD", now,
                UsageCostStatus.LEGACY, null);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO usage_records (id, user_id, document_id, operation, provider, "
                + "service, usage_data, cost_amount, currency, usage_event_id, created_at, cost_status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)", UUID.randomUUID(), userId, documentId,
                lateUsage.operation(), lateUsage.provider(), lateUsage.service(), "{}", lateUsage.costAmount(),
                lateUsage.currency(), lateUsage.usageEventId(), lateUsage.occurredAt(), "LEGACY"))
                .isInstanceOf(RuntimeException.class);

        deletionWorker.disableCognitoImmediately(deletionId); // Disable Cognito before the accepted request is acknowledged.
        assertThat(cognito.disabledSubjects).containsExactlyInAnyOrder(subject, secondSubject);
        deletionWorker.process(deletionId); // Wait for outstanding upload capability, then persist the purge stage.
        deletionWorker.process(deletionId); // Relational purge follows the durable object manifest.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_deletion_objects WHERE deletion_id = ? AND deleted_at IS NULL",
                Integer.class, deletionId)).isEqualTo(2);

        storage.failNextPrivateDelete.set(true);
        assertThatThrownBy(() -> deletionWorker.process(deletionId)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_deletion_objects WHERE deletion_id = ? AND deleted_at IS NULL",
                Integer.class, deletionId)).isEqualTo(2);
        expireWorkerLease();
        deletionWorker.process(deletionId);
        deletionWorker.process(deletionId);
        deletionWorker.process(deletionId); // All objects deleted; advance to Cognito deletion.
        deletionWorker.process(deletionId); // Cognito identity deleted after relational and S3 cleanup.

        assertThat(cognito.disabledSubjects).containsExactlyInAnyOrder(subject, secondSubject);
        assertThat(cognito.deletedSubjects).containsExactlyInAnyOrder(subject, secondSubject);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_identities WHERE user_id = ?", Integer.class,
                userId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT phase FROM account_deletion_jobs WHERE id = ?", String.class, deletionId))
                .isEqualTo("WAIT_IDENTITY_FENCE");

        jdbc.update("UPDATE account_deletion_jobs SET identity_fence_until = CURRENT_TIMESTAMP, available_at = CURRENT_TIMESTAMP, "
                + "enqueued_at = NULL, worker_lease_until = NULL, worker_token = NULL WHERE id = ?", deletionId);
        deletionWorker.process(deletionId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_identities WHERE provider_subject = ?", Integer.class,
                subject)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_identities WHERE provider_subject = ?", Integer.class,
                secondSubject)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_deletion_jobs WHERE id = ?", Integer.class,
                deletionId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_deletion_objects WHERE deletion_id = ?", Integer.class,
                deletionId)).isZero();
        for (String table : Set.of("uploads", "documents", "contracts", "contract_parties", "contract_terms", "events",
                "recommendations", "notifications", "notification_deliveries", "processing_attempts", "usage_records")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + ownerColumn(table) + " = ?",
                    Integer.class, ownerId(table))).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ? AND status = 'ACTIVE'", Integer.class,
                SYSTEM_USER)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE user_id = ? AND resource_type = 'ACCOUNT_DELETION' "
                + "AND resource_id = ? AND action = 'COMPLETED'", Integer.class, SYSTEM_USER, deletionId)).isEqualTo(1);
        assertThat(storage.deletedPrivateObjects).containsExactly(trustedKey);
        assertThat(uploadStorage.deletedQuarantineObjects).containsExactly(quarantineKey);

        assertThat(processing.consume(new ProcessingPublisherPort.ProcessingMessage(documentId)))
                .isEqualTo(ProcessingOrchestratorService.Outcome.ALREADY_TERMINAL);
        accounting.consume(lateUsage);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_records WHERE usage_event_id = ?", Integer.class,
                lateUsage.usageEventId())).isZero();
        assertThatThrownBy(() -> ingestion.process(new UploadIngestionService.ObjectCreatedCommand(uploadId, quarantineKey)))
                .isInstanceOf(UploadIngestionService.UploadNotFoundException.class);
        assertThat(uploadStorage.deletedQuarantineObjects).containsExactly(quarantineKey);
        assertThat(uploadStorage.deletedTrustedUploadIds).containsExactly(uploadId);
    }

    private void insertAccountData(OffsetDateTime now, String quarantineKey, String trustedKey) {
        jdbc.update("INSERT INTO users (id, status, created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?)", userId, now, now);
        jdbc.update("INSERT INTO user_identities (id, user_id, provider, provider_subject, email, created_at) "
                + "VALUES (?, ?, 'cognito', ?, 'owner@example.test', ?)", UUID.randomUUID(), userId, subject, now);
        jdbc.update("INSERT INTO user_identities (id, user_id, provider, provider_subject, email, created_at) "
                + "VALUES (?, ?, 'cognito', ?, 'owner@example.test', ?)", UUID.randomUUID(), userId, secondSubject, now);
        jdbc.update("INSERT INTO uploads (id, user_id, status, object_key, expires_at, created_at, original_filename, contract_type) "
                + "VALUES (?, ?, 'COMPLETED', ?, ?, ?, 'source.pdf', 'ELECTRICITY')", uploadId, userId, quarantineKey,
                now.minusMinutes(1), now);
        jdbc.update("INSERT INTO documents (id, user_id, storage_key, filename, mime_type, size, status, created_at, updated_at, contract_type) "
                + "VALUES (?, ?, ?, 'source.pdf', 'application/pdf', 10, 'SUCCESS', ?, ?, 'ELECTRICITY')",
                documentId, userId, trustedKey, now, now);
        jdbc.update("INSERT INTO contracts (document_id, type, status, data, version, created_at, updated_at) "
                + "VALUES (?, 'ELECTRICITY', 'DRAFT', '{}'::jsonb, 1, ?, ?)", documentId, now, now);
        jdbc.update("INSERT INTO contract_parties (id, contract_id, role, name) VALUES (?, ?, 'PROVIDER', 'Example')",
                UUID.randomUUID(), documentId);
        jdbc.update("INSERT INTO contract_terms (contract_id) VALUES (?)", documentId);
        jdbc.update("INSERT INTO events (id, user_id, document_id, type, description, event_date, status, created_at) "
                + "VALUES (?, ?, ?, 'CONTRACT_EXPIRING', 'Expiry', ?, 'ACTIVE', ?)", eventId, userId, documentId, now, now);
        jdbc.update("INSERT INTO recommendations (id, user_id, document_id, type, data, created_at) "
                + "VALUES (?, ?, ?, 'CONTRACT_EXPIRY', '{}'::jsonb, ?)", recommendationId, userId, documentId, now);
        jdbc.update("INSERT INTO processing_attempts (id, document_id, attempt_number, status, started_at) "
                + "VALUES (?, ?, 1, 'SUCCEEDED', ?)", attemptId, documentId, now);
        jdbc.update("INSERT INTO usage_records (id, user_id, document_id, operation, provider, service, usage_data, "
                + "cost_amount, currency, usage_event_id, created_at) VALUES (?, ?, ?, 'OCR', 'AWS', 'TEXTRACT', '{}'::jsonb, "
                + "1.0000, 'USD', ?, ?)", usageId, userId, documentId, UUID.randomUUID(), now);
        jdbc.update("INSERT INTO audit_events (id, user_id, resource_type, resource_id, action, created_at) "
                + "VALUES (?, ?, 'USER', ?, 'CREATED', ?)", ownerAuditId, userId, userId, now);
        jdbc.update("INSERT INTO notifications (id, recommendation_id, notification_type, data, created_at) "
                + "VALUES (?, ?, 'RECOMMENDATION', '{}'::jsonb, ?)", notificationId, recommendationId, now);
        jdbc.update("INSERT INTO notification_deliveries (id, notification_id, channel, status, destination, created_at, updated_at) "
                + "VALUES (?, ?, 'EMAIL', 'QUEUED', 'owner@example.test', ?, ?)", deliveryId, notificationId, now, now);
    }

    private void expireWorkerLease() {
        jdbc.update("UPDATE account_deletion_jobs SET worker_lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second', "
                + "available_at = CURRENT_TIMESTAMP, enqueued_at = NULL WHERE id = ?", deletionId);
    }

    private String ownerColumn(String table) {
        return switch (table) {
            case "contract_parties", "contract_terms", "contracts", "processing_attempts", "notifications",
                    "notification_deliveries" -> table.equals("notifications") || table.equals("notification_deliveries")
                    ? "id" : table.equals("contract_parties") ? "contract_id" : table.equals("contract_terms")
                    ? "contract_id" : table.equals("contracts") ? "document_id" : "document_id";
            default -> "user_id";
        };
    }

    private UUID ownerId(String table) {
        return switch (table) {
            case "notifications" -> notificationId;
            case "notification_deliveries" -> deliveryId;
            case "contract_parties", "contract_terms", "contracts", "processing_attempts" -> documentId;
            default -> userId;
        };
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DeletionTestPorts {
        @Bean @Primary TestCognito cognitoAccountAdministrationPort() { return new TestCognito(); }
        @Bean @Primary TestStorage objectStoragePort() { return new TestStorage(); }
        @Bean @Primary TestUploadStorage uploadObjectStoragePort() { return new TestUploadStorage(); }
        @Bean @Primary DocumentDeletionQueuePort documentDeletionQueuePort() {
            return message -> { throw new IllegalStateException("simulated post-commit queue failure"); };
        }
        @Bean @Primary AccountDeletionQueuePort accountDeletionQueuePort() {
            return new AccountDeletionQueuePort() {
                @Override public void publish(UUID deletionId) { }
                @Override public boolean configured() { return true; }
            };
        }
    }

    static final class TestCognito implements CognitoAccountAdministrationPort {
        private final java.util.List<String> disabledSubjects = new java.util.ArrayList<>();
        private final java.util.List<String> deletedSubjects = new java.util.ArrayList<>();
        @Override public void disable(String provider, String providerSubject) { disabledSubjects.add(providerSubject); }
        @Override public void delete(String provider, String providerSubject) { deletedSubjects.add(providerSubject); }
    }

    static final class TestStorage implements ObjectStoragePort {
        private final Set<String> deletedPrivateObjects = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean failNextPrivateDelete = new AtomicBoolean();
        @Override public UploadInstructions presignPut(String key, String contentType, long length) {
            return new UploadInstructions(URI.create("https://storage.example.test/" + key), java.util.Map.of());
        }
        @Override public void deletePrivateObject(String objectKey) {
            if (failNextPrivateDelete.compareAndSet(true, false)) throw new IllegalStateException("transient S3 failure");
            deletedPrivateObjects.add(objectKey);
        }
    }

    static final class TestUploadStorage implements UploadObjectStoragePort {
        private final Set<String> deletedQuarantineObjects = ConcurrentHashMap.newKeySet();
        private final Set<UUID> deletedTrustedUploadIds = ConcurrentHashMap.newKeySet();
        @Override public ObjectMetadata head(String objectKey) { throw new UnsupportedOperationException(); }
        @Override public String trustedKeyFor(UUID id) { return "private/trusted/documents/" + id; }
        @Override public void promoteToTrusted(String sourceKey, String trustedKey) { throw new UnsupportedOperationException(); }
        @Override public ObjectMetadata headTrusted(String objectKey) { throw new UnsupportedOperationException(); }
        @Override public byte[] readPrefix(String objectKey, int maximumBytes) { throw new UnsupportedOperationException(); }
        @Override public void deleteQuarantineObject(String objectKey) { deletedQuarantineObjects.add(objectKey); }
        @Override public void deleteTrustedObjectFor(UUID uploadId) { deletedTrustedUploadIds.add(uploadId); }
    }
}
