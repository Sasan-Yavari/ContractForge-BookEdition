package io.contractforge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.contractforge.backend.application.DocumentProcessingService;
import io.contractforge.backend.application.extraction.ContractExtractionPort;
import io.contractforge.backend.application.contract.ContractReviewService;
import io.contractforge.backend.application.processing.ProcessingAttemptLifecycleService;
import io.contractforge.backend.application.processing.ProcessingDlqService;
import io.contractforge.backend.application.monitoring.ContractMonitoringExecutionService;
import io.contractforge.backend.application.notification.NotificationGenerationService;
import io.contractforge.backend.application.port.ProcessingPublisherPort.ProcessingMessage;
import io.contractforge.backend.application.port.RepositoryPorts;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import io.contractforge.backend.domain.model.PersistenceModels;
import io.contractforge.backend.domain.model.PersistenceModels.Contract;
import io.contractforge.backend.domain.model.PersistenceModels.ContractAggregate;
import io.contractforge.backend.domain.model.PersistenceModels.ContractParty;
import io.contractforge.backend.domain.model.PersistenceModels.ContractTerms;
import io.contractforge.backend.domain.model.PersistenceModels.Document;
import io.contractforge.backend.domain.model.PersistenceModels.Event;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import io.contractforge.backend.domain.model.PersistenceModels.Recommendation;
import io.contractforge.backend.domain.model.PersistenceModels.UsageRecord;
import io.contractforge.backend.domain.model.PersistenceModels.Upload;
import io.contractforge.backend.domain.model.PersistenceModels.MonitoringContract;
import io.contractforge.backend.domain.model.DocumentStatus;
import io.contractforge.backend.domain.model.ProcessingAttemptStatus;
import io.contractforge.backend.domain.model.UploadStatus;
import io.contractforge.backend.domain.model.UsageCostStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JdbcRepositoryIntegrationTests extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID PARTY_ID = UUID.fromString("60000000-0000-0000-0000-000000000002");
    private static final UUID EVENT_ID = UUID.fromString("60000000-0000-0000-0000-000000000003");
    private static final UUID RECOMMENDATION_ID = UUID.fromString("60000000-0000-0000-0000-000000000004");
    private static final UUID USAGE_ID = UUID.fromString("60000000-0000-0000-0000-000000000005");
    private static final UUID USAGE_EVENT_ID = UUID.fromString("60000000-0000-0000-0000-000000000006");
    private static final UUID UPLOAD_ID = UUID.fromString("60000000-0000-0000-0000-000000000007");
    private static final UUID NOTIFICATION_IDENTITY_ID = UUID.fromString("60000000-0000-0000-0000-000000000008");

    @MockitoSpyBean
    private JdbcTemplate jdbc;

    @Autowired
    private RepositoryPorts.DocumentRepository documents;

    @Autowired
    private RepositoryPorts.ContractRepository contracts;

    @Autowired
    private RepositoryPorts.EventRepository events;

    @Autowired
    private RepositoryPorts.RecommendationRepository recommendations;

    @Autowired
    private RepositoryPorts.UserIdentityRepository identities;

    @Autowired
    private NotificationGenerationService notificationGeneration;

    @Autowired
    private RepositoryPorts.UsageRecordRepository usageRecords;

    @Autowired
    private RepositoryPorts.UploadRepository uploads;

    @Autowired
    private RepositoryPorts.ProcessingAttemptRepository attempts;

    @Autowired
    private DocumentProcessingService processingService;

    @Autowired
    private ProcessingAttemptLifecycleService attemptLifecycle;

    @Autowired
    private ProcessingDlqService processingDlq;

    @Autowired
    private ContractMonitoringExecutionService monitoringExecution;

    @Autowired
    private ContractReviewService contractReviewService;

    @AfterEach
    void cleanTestData() {
        jdbc.update("DELETE FROM uploads WHERE id = ?", UPLOAD_ID);
        jdbc.update("DELETE FROM documents WHERE id = ?", DOCUMENT_ID);
        jdbc.update("DELETE FROM user_identities WHERE id = ?", NOTIFICATION_IDENTITY_ID);
    }

    @Test
    void savesAndLoadsDocumentContractAggregateWithoutLoadingUnrelatedRows() {
        documents.save(document("PROCESSING"));
        ContractAggregate aggregate = aggregate("ELECTRICITY", "{\"customer\":{\"name\":\"Ada\"}}",
                "{\"consumption\":{\"annual_kwh\":3200}}");
        contracts.save(aggregate);

        ContractAggregate loaded = contracts.findByDocumentId(DOCUMENT_ID).orElseThrow();

        assertThat(loaded.contract().data().value()).contains("Ada");
        assertThat(loaded.parties()).extracting(ContractParty::name).containsExactly("Ada Lovelace");
        assertThat(loaded.terms().electricityData().value()).contains("annual_kwh");
        assertThat(events.findById(EVENT_ID)).isEmpty();
    }

    @Test
    void ownedDocumentDeletionCascadesBusinessStateButRetainsUploadHistory() {
        UUID documentId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        try {
            documents.save(new Document(documentId, USER_ID, "private/trusted/documents/delete-me", "delete.pdf",
                    "application/pdf", 1, "SUCCESS", now(), now()));
            contracts.save(new ContractAggregate(new Contract(documentId, "ELECTRICITY", "DRAFT",
                    new JsonValue("{}"), now(), now(), null), List.of(), null));
            events.save(new Event(UUID.randomUUID(), USER_ID, documentId, "OTHER", "delete", now(), null, null,
                    "ACTIVE", null, now()));
            recommendations.save(new Recommendation(UUID.randomUUID(), USER_ID, documentId, "RENEWAL",
                    new JsonValue("{}"), now(), null));
            usageRecords.save(new UsageRecord(UUID.randomUUID(), USER_ID, documentId, "EXTRACT", "provider", "service",
                    new JsonValue("{}"), BigDecimal.ONE, "EUR", UUID.randomUUID(), now()));
            uploads.save(new Upload(uploadId, USER_ID, UploadStatus.COMPLETED, "quarantine/incoming/" + uploadId,
                    "delete.pdf", now().plusDays(1), now()));

            assertThat(documents.deleteOwnedById(documentId, USER_ID))
                    .contains("private/trusted/documents/delete-me");
            assertThat(documents.findById(documentId)).isEmpty();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contracts WHERE document_id = ?", Integer.class, documentId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE document_id = ?", Integer.class, documentId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendations WHERE document_id = ?", Integer.class, documentId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_records WHERE document_id = ?", Integer.class, documentId)).isZero();
            assertThat(uploads.findById(uploadId)).isPresent();
        } finally {
            jdbc.update("DELETE FROM uploads WHERE id = ?", uploadId);
            jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
        }
    }

    @Test
    void monitoringReadReturnsOnlyActiveElectricityContractsWithOwnerAndApprovedFacts() {
        UUID activeDocument = UUID.randomUUID();
        UUID otherActiveDocument = UUID.randomUUID();
        UUID draftDocument = UUID.randomUUID();
        UUID archivedDocument = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO users (id, status, created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?)",
                    otherUser, now(), now());
            documents.save(new Document(activeDocument, USER_ID, "active", "active.pdf", "application/pdf", 1,
                    "SUCCESS", now(), now()));
            contracts.save(new ContractAggregate(new Contract(activeDocument, "ELECTRICITY", "ACTIVE",
                    new JsonValue("{\"approved\":true}"), now(), now(), null),
                    List.of(new ContractParty(UUID.randomUUID(), activeDocument, "CUSTOMER", "Active Customer")),
                    new ContractTerms(activeDocument, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), 12,
                            "FIXED_TERM", "FIXED", new JsonValue("{\"consumption\":{\"annual_kwh\":100}}"))));
            documents.save(new Document(otherActiveDocument, otherUser, "other-active", "other-active.pdf", "application/pdf", 1,
                    "SUCCESS", now(), now()));
            contracts.save(new ContractAggregate(new Contract(otherActiveDocument, "ELECTRICITY", "ACTIVE",
                    new JsonValue("{\"approved\":\"other\"}"), now(), now(), null),
                    List.of(new ContractParty(UUID.randomUUID(), otherActiveDocument, "CUSTOMER", "Other Customer")),
                    new ContractTerms(otherActiveDocument, LocalDate.of(2027, 1, 1), LocalDate.of(2028, 1, 1), 12,
                            "FIXED_TERM", "FIXED", new JsonValue("{\"consumption\":{\"annual_kwh\":200}}"))));
            documents.save(new Document(draftDocument, otherUser, "draft", "draft.pdf", "application/pdf", 1,
                    "SUCCESS", now(), now()));
            contracts.save(new ContractAggregate(new Contract(draftDocument, "ELECTRICITY", "DRAFT",
                    new JsonValue("{\"actionable\":true}"), now(), now(), null), List.of(), null));
            documents.save(new Document(archivedDocument, USER_ID, "archived", "archived.pdf", "application/pdf", 1,
                    "SUCCESS", now(), now()));
            contracts.save(new ContractAggregate(new Contract(archivedDocument, "ELECTRICITY", "ARCHIVED",
                    new JsonValue("{\"actionable\":true}"), now(), now(), now()), List.of(), null));

            clearInvocations(jdbc);
            List<MonitoringContract> eligible = contracts.findEligibleForMonitoring();

            verify(jdbc, times(1)).query(anyString(), any(ResultSetExtractor.class), any(Object[].class));

            assertThat(eligible).extracting(MonitoringContract::userId, MonitoringContract::documentId)
                    .containsExactlyInAnyOrder(tuple(USER_ID, activeDocument), tuple(otherUser, otherActiveDocument));
            assertThat(eligible).filteredOn(monitoring -> monitoring.documentId().equals(activeDocument))
                    .singleElement().satisfies(monitoring -> {
                        assertThat(monitoring.aggregate().parties()).extracting(ContractParty::name)
                                .containsExactly("Active Customer");
                        assertThat(monitoring.aggregate().terms().endDate()).isEqualTo(LocalDate.of(2027, 1, 1));
                    });
            assertThat(eligible).filteredOn(monitoring -> monitoring.documentId().equals(otherActiveDocument))
                    .singleElement().satisfies(monitoring -> {
                        assertThat(monitoring.userId()).isEqualTo(otherUser);
                        assertThat(monitoring.aggregate().parties()).extracting(ContractParty::name)
                                .containsExactly("Other Customer");
                        assertThat(monitoring.aggregate().terms().endDate()).isEqualTo(LocalDate.of(2028, 1, 1));
                    });
        } finally {
            jdbc.update("DELETE FROM documents WHERE id IN (?, ?, ?, ?)", activeDocument, otherActiveDocument,
                    draftDocument, archivedDocument);
            jdbc.update("DELETE FROM users WHERE id = ?", otherUser);
        }
    }

    @Test
    void monitoringExecutionDeduplicatesRepeatedRunAndPersistsLaterExpiryOccurrence() {
        UUID documentId = UUID.randomUUID();
        try {
            documents.save(new Document(documentId, USER_ID, "monitoring", "monitoring.pdf", "application/pdf", 1,
                    "SUCCESS", now(), now()));
            contracts.save(new ContractAggregate(new Contract(documentId, "ELECTRICITY", "ACTIVE", new JsonValue("{}"),
                    now(), now(), null), List.of(),
                    new ContractTerms(documentId, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 8, 1), 12,
                            "FIXED_TERM", "FIXED", null)));

            var firstRun = monitoringExecution.execute();
            var secondRun = monitoringExecution.execute();
            assertThat(firstRun.persistedEvents()).isEqualTo(1);
            assertThat(firstRun.persistedRecommendations()).isEqualTo(1);
            assertThat(secondRun.persistedEvents()).isZero();
            assertThat(secondRun.persistedRecommendations()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE document_id = ? AND type = 'CONTRACT_EXPIRING'",
                    Integer.class, documentId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendations WHERE document_id = ? AND type = 'CONTRACT_EXPIRY'",
                    Integer.class, documentId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications n JOIN recommendations r "
                    + "ON r.id = n.recommendation_id WHERE r.document_id = ?", Integer.class, documentId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT data -> 'evidence' ->> 'event_type' FROM recommendations WHERE document_id = ?",
                    String.class, documentId)).isEqualTo("CONTRACT_EXPIRING");
            assertThat(jdbc.queryForObject("SELECT user_id FROM events WHERE document_id = ?", UUID.class, documentId))
                    .isEqualTo(USER_ID);
            assertThat(jdbc.queryForObject("SELECT user_id FROM recommendations WHERE document_id = ?", UUID.class, documentId))
                    .isEqualTo(USER_ID);

            jdbc.update("UPDATE recommendations SET expires_at = ? WHERE document_id = ?",
                    now().minus(1, ChronoUnit.DAYS), documentId);
            jdbc.update("DELETE FROM contract_terms WHERE contract_id = ?", documentId);
            jdbc.update("INSERT INTO contract_terms (contract_id, start_date, end_date, contract_duration, "
                            + "renewal_type, pricing_model, electricity_data) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    documentId, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 9, 1), 12,
                    "FIXED_TERM", "FIXED", null);

            var laterRun = monitoringExecution.execute();
            assertThat(laterRun.persistedEvents()).isEqualTo(1);
            assertThat(laterRun.persistedRecommendations()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE document_id = ? AND type = 'CONTRACT_EXPIRING'",
                    Integer.class, documentId)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendations WHERE document_id = ? AND type = 'CONTRACT_EXPIRY'",
                    Integer.class, documentId)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications n JOIN recommendations r "
                    + "ON r.id = n.recommendation_id WHERE r.document_id = ?", Integer.class, documentId)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendations WHERE document_id = ? AND expires_at IS NOT NULL",
                    Integer.class, documentId)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
        }
    }

    @Test
    void monitoringExecutionPersistsAutoRenewalEventWithOwnershipAndStatus() {
        UUID documentId = UUID.randomUUID();
        try {
            documents.save(new Document(documentId, USER_ID, "auto-renew", "auto-renew.pdf", "application/pdf", 1,
                    "SUCCESS", now(), now()));
            contracts.save(new ContractAggregate(new Contract(documentId, "ELECTRICITY", "ACTIVE", new JsonValue("{}"),
                    now(), now(), null), List.of(),
                    new ContractTerms(documentId, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1), 12,
                            "AUTO_RENEW", "FIXED", null)));

            var firstRun = monitoringExecution.execute();
            var secondRun = monitoringExecution.execute();
            assertThat(firstRun.persistedEvents()).isEqualTo(1);
            assertThat(firstRun.persistedRecommendations()).isEqualTo(1);
            assertThat(secondRun.persistedEvents()).isZero();
            assertThat(secondRun.persistedRecommendations()).isZero();
            Map<String, Object> event = jdbc.queryForMap("SELECT type, event_date, status, user_id FROM events WHERE document_id = ?",
                    documentId);
            assertThat(event.get("type")).isEqualTo("CONTRACT_RENEWING");
            assertThat(event.get("event_date")).isInstanceOf(java.sql.Timestamp.class);
            assertThat(((java.sql.Timestamp) event.get("event_date")).toInstant())
                    .isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00Z").toInstant());
            assertThat(event.get("status")).isEqualTo("ACTIVE");
            assertThat(event.get("user_id")).isEqualTo(USER_ID);
            assertThat(jdbc.queryForObject("SELECT type FROM recommendations WHERE document_id = ?", String.class, documentId))
                    .isEqualTo("RENEWAL");
            assertThat(jdbc.queryForObject("SELECT data -> 'evidence' ->> 'event_type' FROM recommendations WHERE document_id = ?",
                    String.class, documentId)).isEqualTo("CONTRACT_RENEWING");
        } finally {
            jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
        }
    }

    @Test
    void concurrentMonitoringExecutionsConvergeOnOneEventOccurrence() throws Exception {
        UUID documentId = UUID.randomUUID();
        documents.save(new Document(documentId, USER_ID, "concurrent-monitoring", "concurrent.pdf", "application/pdf", 1,
                "SUCCESS", now(), now()));
        contracts.save(new ContractAggregate(new Contract(documentId, "ELECTRICITY", "ACTIVE", new JsonValue("{}"),
                now(), now(), null), List.of(),
                new ContractTerms(documentId, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1), 12,
                        "FIXED_TERM", "FIXED", null)));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<int[]> first = executor.submit(() -> {
                start.await();
                var result = monitoringExecution.execute();
                return new int[] {result.persistedEvents(), result.persistedRecommendations()};
            });
            Future<int[]> second = executor.submit(() -> {
                start.await();
                var result = monitoringExecution.execute();
                return new int[] {result.persistedEvents(), result.persistedRecommendations()};
            });
            start.countDown();

            int[] firstResult = first.get();
            int[] secondResult = second.get();
            assertThat(firstResult[0] + secondResult[0]).isEqualTo(1);
            assertThat(firstResult[1] + secondResult[1]).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE document_id = ?", Integer.class, documentId))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendations WHERE document_id = ?", Integer.class, documentId))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
            jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
        }
    }

    @Test
    void roundTripsSupportingJsonbRecordsThroughJdbcRepositories() {
        documents.save(document("PROCESSING"));
        Recommendation recommendation = new Recommendation(RECOMMENDATION_ID, USER_ID, DOCUMENT_ID, "RENEWAL",
                new JsonValue("{\"reason\":\"EXPIRY\",\"impact\":{\"amount\":85}}"), now(), null);
        assertThat(recommendations.saveIfAbsent(recommendation)).isEqualTo(1);
        assertThat(recommendations.saveIfAbsent(recommendation)).isZero();
        assertThat(recommendations.saveIfAbsent(new Recommendation(UUID.randomUUID(), USER_ID, DOCUMENT_ID, "RENEWAL",
                recommendation.data(), now(), now().minus(1, ChronoUnit.DAYS)))).isEqualTo(1);
        usageRecords.save(new UsageRecord(USAGE_ID, USER_ID, DOCUMENT_ID, "EXTRACT", "provider", "service",
                new JsonValue("{\"input_tokens\":120}"), new BigDecimal("0.0100"), "EUR", USAGE_EVENT_ID, now(),
                UsageCostStatus.CALCULATED, "test-price-v1"));

        assertThat(recommendations.findById(RECOMMENDATION_ID).orElseThrow().data().value()).contains("EXPIRY");
        UsageRecord loaded = usageRecords.findByUsageEventId(USAGE_EVENT_ID).orElseThrow();
        assertThat(loaded.usageData().value()).contains("input_tokens");
        assertThat(loaded.costStatus()).isEqualTo(UsageCostStatus.CALCULATED);
        assertThat(loaded.pricingVersion()).isEqualTo("test-price-v1");
    }

    @Test
    void recommendationGenerationCreatesOneSemanticNotificationAndQueuedEmailIntent() {
        documents.save(document("PROCESSING"));
        identities.save(new PersistenceModels.UserIdentity(NOTIFICATION_IDENTITY_ID, USER_ID,
                "notification-test", "owner", "Owner@Example.Test", now()));
        Recommendation recommendation = new Recommendation(RECOMMENDATION_ID, USER_ID, DOCUMENT_ID,
                "CONTRACT_EXPIRY", new JsonValue("{\"reason\":{\"type\":\"CONTRACT_EXPIRY\"},"
                + "\"evidence\":{\"event_type\":\"CONTRACT_EXPIRING\",\"event_date\":\"2026-01-01T00:00:00Z\","
                + "\"source_reference\":\"must-not-be-copied\"},"
                + "\"impact\":{\"type\":\"NONE\"},\"action\":{\"type\":\"REVIEW\"}}"), now(), null);
        recommendations.saveIfAbsent(recommendation);

        notificationGeneration.generate(recommendation);
        notificationGeneration.generate(recommendation);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recommendation_id = ?", Integer.class,
                RECOMMENDATION_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT notification_type FROM notifications WHERE recommendation_id = ?",
                String.class, RECOMMENDATION_ID)).isEqualTo("RECOMMENDATION");
        assertThat(jdbc.queryForObject("SELECT data -> 'reason' ->> 'type' FROM notifications WHERE recommendation_id = ?",
                String.class, RECOMMENDATION_ID)).isEqualTo("CONTRACT_EXPIRY");
        assertThat(jdbc.queryForObject("SELECT data -> 'evidence' ->> 'source_reference' FROM notifications "
                + "WHERE recommendation_id = ?", String.class, RECOMMENDATION_ID)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_deliveries d JOIN notifications n "
                        + "ON n.id = d.notification_id WHERE n.recommendation_id = ?", Integer.class,
                RECOMMENDATION_ID)).isEqualTo(1);
        Map<String, Object> delivery = jdbc.queryForMap("SELECT d.channel, d.status, d.destination, d.attempt_count "
                + "FROM notification_deliveries d JOIN notifications n ON n.id = d.notification_id "
                + "WHERE n.recommendation_id = ?", RECOMMENDATION_ID);
        assertThat(delivery).containsEntry("channel", "EMAIL").containsEntry("status", "QUEUED")
                .containsEntry("destination", "owner@example.test").containsEntry("attempt_count", 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'notifications' AND column_name = 'owner_id'",
                Integer.class)).isZero();
    }

    @Test
    void unavailableTrustedDestinationCreatesTerminalFailedEmailIntent() {
        documents.save(document("PROCESSING"));
        UUID recommendationId = UUID.randomUUID();
        Recommendation recommendation = new Recommendation(recommendationId, USER_ID, DOCUMENT_ID,
                "CONTRACT_EXPIRY", new JsonValue("{\"reason\":{\"type\":\"CONTRACT_EXPIRY\"},"
                + "\"evidence\":{\"event_type\":\"CONTRACT_EXPIRING\",\"event_date\":\"2026-01-01T00:00:00Z\"},"
                + "\"impact\":{\"type\":\"NONE\"},\"action\":{\"type\":\"REVIEW\"}}"), now(), null);
        recommendations.saveIfAbsent(recommendation);

        notificationGeneration.generate(recommendation);

        Map<String, Object> delivery = jdbc.queryForMap("SELECT d.status, d.destination, d.attempt_count, "
                + "d.failure_code, d.last_attempt_at FROM notification_deliveries d JOIN notifications n "
                + "ON n.id = d.notification_id WHERE n.recommendation_id = ?", recommendationId);
        assertThat(delivery).containsEntry("status", "FAILED").containsEntry("destination", null)
                .containsEntry("attempt_count", 0).containsEntry("failure_code", "RECIPIENT_UNAVAILABLE")
                .containsEntry("last_attempt_at", null);
    }

    @Test
    void duplicateUsageDeliveryConvergesToOneRecordConcurrently() throws Exception {
        documents.save(document("PROCESSING"));
        UsageRecord record = new UsageRecord(USAGE_ID, USER_ID, DOCUMENT_ID, "EXTRACT", "provider", "service",
                new JsonValue("{\"input_tokens\":120}"), new BigDecimal("0.0100"), "EUR", USAGE_EVENT_ID, now());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> { start.await(); return usageRecords.saveIfAbsent(record); });
            Future<Integer> second = executor.submit(() -> { start.await(); return usageRecords.saveIfAbsent(new UsageRecord(
                    UUID.randomUUID(), USER_ID, DOCUMENT_ID, "EXTRACT", "provider", "service", record.usageData(),
                    record.costAmount(), record.currency(), USAGE_EVENT_ID, now())); });
            start.countDown();
            assertThat(first.get() + second.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usage_records WHERE usage_event_id = ?", Integer.class,
                    USAGE_EVENT_ID)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void uploadRepositoryRoundTripsApprovedUploadRepresentation() {
        OffsetDateTime createdAt = now().truncatedTo(ChronoUnit.MICROS);
        Upload upload = new Upload(UPLOAD_ID, USER_ID, UploadStatus.UPLOADING, "quarantine/incoming/" + UPLOAD_ID, "contract.pdf",
                createdAt.plusMinutes(15), createdAt);

        uploads.save(upload);

        Upload loaded = uploads.findById(UPLOAD_ID).orElseThrow();
        assertThat(loaded.id()).isEqualTo(upload.id());
        assertThat(loaded.userId()).isEqualTo(upload.userId());
        assertThat(loaded.status()).isEqualTo(upload.status());
        assertThat(loaded.objectKey()).isEqualTo(upload.objectKey());
        assertThat(loaded.originalFilename()).isEqualTo(upload.originalFilename());
        assertThat(loaded.expiresAt().toInstant()).isEqualTo(upload.expiresAt().toInstant());
        assertThat(loaded.createdAt().toInstant()).isEqualTo(upload.createdAt().toInstant());
    }

    @Test
    void guardedUploadTransitionsConvergeUnderDuplicateDelivery() throws Exception {
        OffsetDateTime createdAt = now().truncatedTo(ChronoUnit.MICROS);
        uploads.save(new Upload(UPLOAD_ID, USER_ID, UploadStatus.UPLOADING, "quarantine/incoming/" + UPLOAD_ID, "contract.pdf",
                createdAt.plusMinutes(15), createdAt));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> {
                start.await();
                return uploads.markUploaded(UPLOAD_ID, "quarantine/incoming/" + UPLOAD_ID);
            });
            Future<Integer> second = executor.submit(() -> {
                start.await();
                return uploads.markUploaded(UPLOAD_ID, "quarantine/incoming/" + UPLOAD_ID);
            });
            start.countDown();

            assertThat(first.get() + second.get()).isEqualTo(1);
            assertThat(uploads.findById(UPLOAD_ID).orElseThrow().status()).isEqualTo(UploadStatus.UPLOADED);
            assertThat(uploads.markScanned(UPLOAD_ID)).isEqualTo(1);
            assertThat(uploads.markScanned(UPLOAD_ID)).isZero();
            assertThat(uploads.findById(UPLOAD_ID).orElseThrow().status()).isEqualTo(UploadStatus.SCANNED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectedUploadIsTerminalAndCannotBeAdvanced() {
        OffsetDateTime createdAt = now().truncatedTo(ChronoUnit.MICROS);
        uploads.save(new Upload(UPLOAD_ID, USER_ID, UploadStatus.UPLOADING, "quarantine/incoming/" + UPLOAD_ID, "contract.pdf",
                createdAt.plusMinutes(15), createdAt));

        assertThat(uploads.markRejected(UPLOAD_ID, "quarantine/incoming/" + UPLOAD_ID)).isEqualTo(1);
        assertThat(uploads.markRejected(UPLOAD_ID, "quarantine/incoming/" + UPLOAD_ID)).isZero();
        assertThat(uploads.markUploaded(UPLOAD_ID, "quarantine/incoming/" + UPLOAD_ID)).isZero();
        assertThat(uploads.markScanned(UPLOAD_ID)).isZero();
        assertThat(uploads.findById(UPLOAD_ID).orElseThrow().status()).isEqualTo(UploadStatus.REJECTED);
    }

    @Test
    void repositoriesPersistAndLoadEvent() {
        documents.save(document("PROCESSING"));
        OffsetDateTime eventDate = now().truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime createdAt = now().truncatedTo(ChronoUnit.MICROS);
        Event event = new Event(EVENT_ID, USER_ID, DOCUMENT_ID, "OTHER", "Renewal review", eventDate,
                new BigDecimal("12.50"), "EUR", "ACTIVE", "source-1", createdAt);

        events.save(event);

        PersistenceModels.Event loaded = events.findById(EVENT_ID).orElseThrow();
        assertThat(loaded.id()).isEqualTo(event.id());
        assertThat(loaded.userId()).isEqualTo(event.userId());
        assertThat(loaded.documentId()).isEqualTo(event.documentId());
        assertThat(loaded.type()).isEqualTo(event.type());
        assertThat(loaded.description()).isEqualTo(event.description());
        assertThat(loaded.eventDate()).isEqualTo(event.eventDate());
        assertThat(loaded.amount()).isEqualByComparingTo(event.amount());
        assertThat(loaded.currency()).isEqualTo(event.currency());
        assertThat(loaded.status()).isEqualTo(event.status());
        assertThat(loaded.sourceReference()).isEqualTo(event.sourceReference());
    }

    @Test
    void successfulProcessingCommitsDocumentAndContractTogether() {
        documents.save(document("PROCESSING"));

        ContractAggregate draft = aggregate("ELECTRICITY", "{}", null);
        draft = new ContractAggregate(new Contract(draft.contract().documentId(), draft.contract().type(), "DRAFT",
                draft.contract().data(), draft.contract().createdAt(), draft.contract().updatedAt(), null),
                draft.parties(), draft.terms());
        processingService.completeSuccessfully(DOCUMENT_ID, draft);

        assertThat(documents.findById(DOCUMENT_ID).orElseThrow().status()).isEqualTo(DocumentStatus.SUCCESS);
        assertThat(contracts.findByDocumentId(DOCUMENT_ID).orElseThrow().contract().status())
                .isEqualTo(io.contractforge.backend.domain.model.ContractStatus.DRAFT);
    }

    @Test
    void failedContractWriteRollsBackEarlierDocumentStatusUpdate() {
        documents.save(document("PROCESSING"));
        ContractAggregate invalidAggregate = aggregate("ELECTRICITY", "not-json", null);

        assertThatThrownBy(() -> processingService.completeSuccessfully(DOCUMENT_ID, invalidAggregate))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(documents.findById(DOCUMENT_ID).orElseThrow().status()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(contracts.findByDocumentId(DOCUMENT_ID)).isEmpty();
    }

    @Test
    void concurrentAttemptAllocationUsesTheDocumentLockAndUniqueNumbering() throws Exception {
        documents.save(document("PROCESSING"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<PersistenceModels.ProcessingAttempt> first = executor.submit(() -> {
                start.await();
                return attemptLifecycle.begin(DOCUMENT_ID);
            });
            Future<PersistenceModels.ProcessingAttempt> second = executor.submit(() -> {
                start.await();
                return attemptLifecycle.begin(DOCUMENT_ID);
            });
            start.countDown();

            assertThat(java.util.stream.Stream.of(first.get(), second.get())
                    .filter(java.util.Objects::nonNull)).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processing_attempts WHERE document_id = ?", Integer.class,
                    DOCUMENT_ID)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT MAX(attempt_number) FROM processing_attempts WHERE document_id = ?", Integer.class,
                    DOCUMENT_ID)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSuccessfulCompletionsCreateAtMostOneContract() throws Exception {
        documents.save(document("PROCESSING"));
        ContractAggregate draft = aggregate("ELECTRICITY", "{}", null);
        draft = new ContractAggregate(new Contract(draft.contract().documentId(), draft.contract().type(), "DRAFT",
                draft.contract().data(), draft.contract().createdAt(), draft.contract().updatedAt(), null),
                draft.parties(), draft.terms());
        ContractAggregate completedDraft = draft;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> complete(start, completedDraft));
            Future<Boolean> second = executor.submit(() -> complete(start, completedDraft));
            start.countDown();
            assertThat(java.util.stream.Stream.of(first.get(), second.get()).filter(Boolean.TRUE::equals)).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contracts WHERE document_id = ?", Integer.class,
                    DOCUMENT_ID)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void guardedDraftEditAdvancesVersionOnceAndRejectsStaleData() {
        documents.save(document("SUCCESS"));
        ContractAggregate draft = draftAggregate(schemaVersionJson(), 0);
        contracts.save(draft);

        ContractAggregate edited = draftWithData(draft, schemaVersionJson(",\"edited\":true"), 0);
        assertThat(contracts.updateDraft(edited, 0)).isEqualTo(1);
        assertThat(contracts.updateDraft(draftWithData(draft, schemaVersionJson(",\"stale\":true"), 0), 0)).isZero();

        ContractAggregate loaded = contracts.findByDocumentId(DOCUMENT_ID).orElseThrow();
        assertThat(loaded.contract().version()).isEqualTo(1);
        assertThat(loaded.contract().data().value()).contains("edited").doesNotContain("stale");
    }

    @Test
    void concurrentDraftEditsAllowExactlyOneVersionedWinner() throws Exception {
        documents.save(document("SUCCESS"));
        ContractAggregate draft = draftAggregate(schemaVersionJson(), 0);
        contracts.save(draft);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> { start.await(); return contracts.updateDraft(
                    draftWithData(draft, schemaVersionJson(",\"winner\":1"), 0), 0); });
            Future<Integer> second = executor.submit(() -> { start.await(); return contracts.updateDraft(
                    draftWithData(draft, schemaVersionJson(",\"winner\":2"), 0), 0); });
            start.countDown();
            assertThat(first.get() + second.get()).isEqualTo(1);
            assertThat(contracts.findByDocumentId(DOCUMENT_ID).orElseThrow().contract().version()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentConfirmedEditsAllowExactlyOneVersionedWinnerAndPreserveActive() throws Exception {
        documents.save(document("SUCCESS"));
        ContractAggregate draft = draftAggregate(schemaVersionJson(), 0);
        ContractAggregate active = new ContractAggregate(new Contract(DOCUMENT_ID, draft.contract().type(),
                io.contractforge.backend.domain.model.ContractStatus.ACTIVE,
                draft.contract().data(), 0, draft.contract().createdAt(), draft.contract().updatedAt(), null),
                draft.parties(), draft.terms());
        contracts.save(active);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> { start.await(); return contracts.updateActive(
                    draftWithData(active, schemaVersionJson(",\"winner\":1"), 0), 0); });
            Future<Integer> second = executor.submit(() -> { start.await(); return contracts.updateActive(
                    draftWithData(active, schemaVersionJson(",\"winner\":2"), 0), 0); });
            start.countDown();
            assertThat(first.get() + second.get()).isEqualTo(1);
            ContractAggregate saved = contracts.findByDocumentId(DOCUMENT_ID).orElseThrow();
            assertThat(saved.contract().status()).isEqualTo(io.contractforge.backend.domain.model.ContractStatus.ACTIVE);
            assertThat(saved.contract().version()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentApprovalTransitionsDraftExactlyOnce() throws Exception {
        documents.save(document("SUCCESS"));
        ContractAggregate draft = draftAggregate(schemaVersionJson(), 0);
        contracts.save(draft);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = executor.submit(() -> { start.await(); return contracts.approveDraft(DOCUMENT_ID, 0); });
            Future<Integer> second = executor.submit(() -> { start.await(); return contracts.approveDraft(DOCUMENT_ID, 0); });
            start.countDown();
            assertThat(first.get() + second.get()).isEqualTo(1);
            PersistenceModels.Contract approved = contracts.findByDocumentId(DOCUMENT_ID).orElseThrow().contract();
            assertThat(approved.status()).isEqualTo(io.contractforge.backend.domain.model.ContractStatus.ACTIVE);
            assertThat(approved.version()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentFullDraftEditAndApprovalHaveOneAtomicAggregateWinner() throws Exception {
        documents.save(document("SUCCESS"));
        ContractAggregate draft = draftAggregate(schemaVersionJson(), 0);
        contracts.save(draft);

        var editedElectricityData = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        editedElectricityData.putObject("consumption").put("annual_kwh", 200);
        editedElectricityData.putObject("pricing").put("model", "FIXED");
        var editedTerms = new ContractReviewService.Terms(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1),
                12, "FIXED_TERM", "FIXED", editedElectricityData);
        var edit = new ContractReviewService.Edit(0,
                List.of(new ContractReviewService.Party("CUSTOMER", "Grace")), editedTerms);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MutationOutcome> editResult = executor.submit(() -> {
                ready.countDown();
                start.await();
                return runEdit(edit);
            });
            Future<MutationOutcome> approvalResult = executor.submit(() -> {
                ready.countDown();
                start.await();
                return runApproval();
            });
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();

            MutationOutcome editOutcome = editResult.get();
            MutationOutcome approvalOutcome = approvalResult.get();
            assertThat(java.util.stream.Stream.of(editOutcome, approvalOutcome)
                    .filter(MutationOutcome::succeeded).count()).isEqualTo(1);
            MutationOutcome loser = editOutcome.succeeded() ? approvalOutcome : editOutcome;
            assertThat(loser.failure()).isIn(ContractReviewService.ConflictException.class,
                    ContractReviewService.NotDraftException.class);

            ContractAggregate finalAggregate = contracts.findByDocumentId(DOCUMENT_ID).orElseThrow();
            boolean editWon = editOutcome.succeeded();
            assertThat(finalAggregate.contract().status()).isEqualTo(editWon
                    ? io.contractforge.backend.domain.model.ContractStatus.DRAFT
                    : io.contractforge.backend.domain.model.ContractStatus.ACTIVE);
            assertThat(finalAggregate.contract().version()).isEqualTo(1);
            assertThat(finalAggregate.parties()).hasSize(1);
            assertThat(finalAggregate.parties().get(0).name()).isEqualTo(editWon ? "Grace" : "Ada Lovelace");
            assertThat(finalAggregate.terms()).isNotNull();
            assertThat(finalAggregate.terms().electricityData().value())
                    .contains(editWon ? "200" : "3200");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_parties WHERE contract_id = ?",
                    Integer.class, DOCUMENT_ID)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_terms WHERE contract_id = ?",
                    Integer.class, DOCUMENT_ID)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private MutationOutcome runEdit(ContractReviewService.Edit edit) {
        try {
            contractReviewService.edit(new AuthenticatedApplicationUser(USER_ID), DOCUMENT_ID, edit);
            return MutationOutcome.success();
        } catch (ContractReviewService.ConflictException | ContractReviewService.NotDraftException expected) {
            return MutationOutcome.failure(expected.getClass());
        }
    }

    private MutationOutcome runApproval() {
        try {
            contractReviewService.approve(new AuthenticatedApplicationUser(USER_ID), DOCUMENT_ID, 0);
            return MutationOutcome.success();
        } catch (ContractReviewService.ConflictException | ContractReviewService.NotDraftException expected) {
            return MutationOutcome.failure(expected.getClass());
        }
    }

    private record MutationOutcome(boolean succeeded, Class<? extends RuntimeException> failure) {
        private static MutationOutcome success() {
            return new MutationOutcome(true, null);
        }

        private static MutationOutcome failure(Class<? extends RuntimeException> failure) {
            return new MutationOutcome(false, failure);
        }
    }

    private boolean complete(CountDownLatch start, ContractAggregate draft) throws InterruptedException {
        start.await();
        try {
            processingService.completeSuccessfully(DOCUMENT_ID, draft);
            return true;
        } catch (RuntimeException expectedDuplicate) {
            return false;
        }
    }

    @Test
    void dlqFailureTransitionFinalizesRunningAttemptIdempotently() {
        documents.save(document("PROCESSING"));
        PersistenceModels.ProcessingAttempt attempt = attemptLifecycle.begin(DOCUMENT_ID);

        processingDlq.handle(new ProcessingMessage(DOCUMENT_ID));
        processingDlq.handle(new ProcessingMessage(DOCUMENT_ID));

        assertThat(documents.findById(DOCUMENT_ID).orElseThrow().status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(attempts.findById(attempt.id()).orElseThrow().status()).isEqualTo(ProcessingAttemptStatus.FAILED);
    }

    private ContractAggregate aggregate(String type, String data, String electricityData) {
        Contract contract = new Contract(DOCUMENT_ID, type, "ACTIVE", new JsonValue(data), now(), now(), null);
        ContractTerms terms = electricityData == null ? null : new ContractTerms(DOCUMENT_ID, LocalDate.of(2026, 1, 1),
                LocalDate.of(2027, 1, 1), 12, "FIXED_TERM", "FIXED", new JsonValue(electricityData));
        return new ContractAggregate(contract, java.util.List.of(
                new ContractParty(PARTY_ID, DOCUMENT_ID, "CUSTOMER", "Ada Lovelace")), terms);
    }

    private String schemaVersionJson() {
        return schemaVersionJson("");
    }

    private String schemaVersionJson(String suffix) {
        return "{\"schema_version\":\"" + ContractExtractionPort.CURRENT_SCHEMA_VERSION + "\"" + suffix + "}";
    }

    private ContractAggregate draftAggregate(String data, long version) {
        ContractAggregate source = aggregate("ELECTRICITY", data, "{\"consumption\":{\"annual_kwh\":3200}}");
        return new ContractAggregate(new Contract(DOCUMENT_ID, "ELECTRICITY", "DRAFT", source.contract().data(),
                version, source.contract().createdAt(), source.contract().updatedAt(), null), source.parties(), source.terms());
    }

    private ContractAggregate draftWithData(ContractAggregate source, String data, long version) {
        return new ContractAggregate(new Contract(DOCUMENT_ID, source.contract().type(), source.contract().status(), new JsonValue(data),
                version, source.contract().createdAt(), source.contract().updatedAt(), null), source.parties(), source.terms());
    }

    private Document document(String status) {
        return new Document(DOCUMENT_ID, USER_ID, "test/document", "contract.pdf", "application/pdf", 42L,
                status, now(), now());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now();
    }
}
