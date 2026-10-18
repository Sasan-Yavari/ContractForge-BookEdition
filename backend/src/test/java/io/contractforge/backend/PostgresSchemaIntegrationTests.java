package io.contractforge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PostgresSchemaIntegrationTests extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CONTRACT_ID = DOCUMENT_ID;
    private static final UUID PARTY_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID EVENT_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID RECOMMENDATION_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID ATTEMPT_ID = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID NOTIFICATION_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID DELIVERY_ID = UUID.fromString("70000000-0000-0000-0000-000000000002");

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanTestData() {
        jdbc.update("DELETE FROM documents WHERE id = ?", DOCUMENT_ID);
    }

    @Test
    void appliesAllApprovedMigrationsAndRecordsSchemaHistory() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank",
                String.class);

        assertThat(versions).containsExactly(
                "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11",
                "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' "
                        + "AND indexname IN ('uploads_owner_collection_idx', 'documents_owner_collection_idx', "
                        + "'contract_parties_contract_role_idx', 'events_owner_contract_history_idx', "
                        + "'recommendations_owner_contract_history_idx')", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND column_name = 'contract_type' "
                        + "AND table_name IN ('uploads', 'documents')", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_constraint "
                        + "WHERE conname IN ('uploads_contract_type_check', 'documents_contract_type_check')",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name IN "
                        + "('users', 'user_identities', 'documents', 'contracts', "
                        + "'contract_parties', 'contract_terms', 'events', 'recommendations', "
                        + "'processing_attempts', 'audit_events', 'uploads', 'usage_records', "
                        + "'notifications', 'notification_deliveries', 'account_deletion_jobs', "
                        + "'account_deletion_objects')",
                Integer.class)).isEqualTo(16);
    }

    @Test
    void enforcesAccountWriteFenceForeignKeysAndCheckConstraints() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO documents (id, user_id, storage_key, filename, mime_type, size, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                DOCUMENT_ID, UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), "key", "file.pdf",
                "application/pdf", 1L, "PROCESSING", OffsetDateTime.now(), OffsetDateTime.now()))
                .isInstanceOf(UncategorizedSQLException.class)
                .satisfies(failure -> assertThat(((UncategorizedSQLException) failure).getSQLException().getSQLState())
                        .isEqualTo("55000"));

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO account_deletion_objects (deletion_id, object_key, object_kind) VALUES (?, ?, 'TRUSTED')",
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), "private/missing"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO documents (id, user_id, storage_key, filename, mime_type, size, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                DOCUMENT_ID, USER_ID, "key", "file.pdf", "application/pdf", -1L, "PROCESSING",
                OffsetDateTime.now(), OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesNotificationUniquenessAndDeliveryValueConstraints() {
        insertDocument();
        jdbc.update("INSERT INTO recommendations (id, user_id, document_id, type, data, created_at) "
                        + "VALUES (?, ?, ?, 'RENEWAL', '{}', ?)",
                RECOMMENDATION_ID, USER_ID, DOCUMENT_ID, OffsetDateTime.now());
        jdbc.update("INSERT INTO notifications (id, recommendation_id, notification_type, data, created_at) "
                        + "VALUES (?, ?, 'RECOMMENDATION', '{}', ?)",
                NOTIFICATION_ID, RECOMMENDATION_ID, OffsetDateTime.now());

        assertThatThrownBy(() -> jdbc.update("INSERT INTO notifications "
                        + "(id, recommendation_id, notification_type, data, created_at) VALUES (?, ?, 'RECOMMENDATION', '{}', ?)",
                UUID.randomUUID(), RECOMMENDATION_ID, OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO notification_deliveries "
                        + "(id, notification_id, channel, status, created_at, updated_at) VALUES (?, ?, 'SMS', 'QUEUED', ?, ?)",
                DELIVERY_ID, NOTIFICATION_ID, OffsetDateTime.now(), OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cascadesDocumentOwnedRowsAccordingToMigrationRules() {
        insertDocument();
        jdbc.update("INSERT INTO contracts (document_id, type, status, data, created_at, updated_at) "
                        + "VALUES (?, 'ELECTRICITY', 'ACTIVE', '{\"source\":\"test\"}', ?, ?)",
                CONTRACT_ID, OffsetDateTime.now(), OffsetDateTime.now());
        jdbc.update("INSERT INTO contract_parties (id, contract_id, role, name) VALUES (?, ?, 'CUSTOMER', 'Test Customer')",
                PARTY_ID, CONTRACT_ID);
        jdbc.update("INSERT INTO events (id, user_id, document_id, type, description, event_date, status, created_at) "
                        + "VALUES (?, ?, ?, 'OTHER', 'test event', ?, 'ACTIVE', ?)",
                EVENT_ID, USER_ID, DOCUMENT_ID, OffsetDateTime.now(), OffsetDateTime.now());
        jdbc.update("INSERT INTO recommendations (id, user_id, document_id, type, data, created_at) "
                        + "VALUES (?, ?, ?, 'RENEWAL', '{}', ?)",
                RECOMMENDATION_ID, USER_ID, DOCUMENT_ID, OffsetDateTime.now());
        jdbc.update("INSERT INTO notifications (id, recommendation_id, notification_type, data, created_at) "
                        + "VALUES (?, ?, 'RECOMMENDATION', '{\"reason\":{\"type\":\"RENEWAL\"}}', ?)",
                NOTIFICATION_ID, RECOMMENDATION_ID, OffsetDateTime.now());
        jdbc.update("INSERT INTO notification_deliveries (id, notification_id, channel, status, destination, created_at, updated_at) "
                        + "VALUES (?, ?, 'EMAIL', 'QUEUED', 'owner@example.test', ?, ?)",
                DELIVERY_ID, NOTIFICATION_ID, OffsetDateTime.now(), OffsetDateTime.now());
        jdbc.update("INSERT INTO processing_attempts (id, document_id, attempt_number, status, started_at) "
                        + "VALUES (?, ?, 1, 'RUNNING', ?)", ATTEMPT_ID, DOCUMENT_ID, OffsetDateTime.now());

        jdbc.update("DELETE FROM documents WHERE id = ?", DOCUMENT_ID);

        assertThat(count("contracts", "document_id", CONTRACT_ID)).isZero();
        assertThat(count("contract_parties", "contract_id", CONTRACT_ID)).isZero();
        assertThat(count("events", "document_id", DOCUMENT_ID)).isZero();
        assertThat(count("recommendations", "document_id", DOCUMENT_ID)).isZero();
        assertThat(count("notifications", "id", NOTIFICATION_ID)).isZero();
        assertThat(count("notification_deliveries", "id", DELIVERY_ID)).isZero();
        assertThat(count("processing_attempts", "document_id", DOCUMENT_ID)).isZero();
    }

    @Test
    void storesAndReadsJsonbUsingJdbc() throws Exception {
        insertDocument();
        String json = "{\"consumption\":{\"annual_kwh\":3200},\"pricing\":{\"model\":\"FIXED\"}}";
        jdbc.update("INSERT INTO contracts (document_id, type, status, data, created_at, updated_at) "
                        + "VALUES (?, 'ELECTRICITY', 'ACTIVE', ?::jsonb, ?, ?)",
                CONTRACT_ID, json, OffsetDateTime.now(), OffsetDateTime.now());

        Boolean containsStoredDocument = jdbc.queryForObject(
                "SELECT data @> ?::jsonb FROM contracts WHERE document_id = ?", Boolean.class, json, CONTRACT_ID);

        assertThat(containsStoredDocument).isTrue();
    }

    private void insertDocument() {
        jdbc.update("INSERT INTO documents (id, user_id, storage_key, filename, mime_type, size, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                DOCUMENT_ID, USER_ID, "test/document", "contract.pdf", "application/pdf", 42L, "PROCESSING",
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private int count(String table, String column, UUID value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
    }
}
