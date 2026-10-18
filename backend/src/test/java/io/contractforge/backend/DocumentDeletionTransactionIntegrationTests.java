package io.contractforge.backend;

import static org.assertj.core.api.Assertions.assertThat;

import io.contractforge.backend.application.document.DocumentDeletionQueuePort;
import io.contractforge.backend.application.document.DocumentDeletionService;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@ActiveProfiles("integration")
@Import(DocumentDeletionTransactionIntegrationTests.QueueConfiguration.class)
class DocumentDeletionTransactionIntegrationTests extends PostgresIntegrationTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DocumentDeletionService deletions;
    @Autowired private TransactionTemplate transactions;
    @Autowired private RecordingQueue queue;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        queue.messages.clear();
    }

    @Test
    void committedDeletionPublishesAfterCommitAndRemovesDocument() {
        seedDocument();

        deletions.delete(new AuthenticatedApplicationUser(userId), documentId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE id = ?", Integer.class, documentId)).isZero();
        assertThat(queue.messages).containsExactly(new DocumentDeletionQueuePort.DocumentDeletionMessage(
                "private/trusted/documents/transaction-test"));
    }

    @Test
    void rolledBackDeletionPublishesNothingAndRetainsDocument() {
        seedDocument();

        transactions.executeWithoutResult(status -> {
            deletions.delete(new AuthenticatedApplicationUser(userId), documentId);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE id = ?", Integer.class, documentId)).isEqualTo(1);
        assertThat(queue.messages).isEmpty();
    }

    private void seedDocument() {
        jdbc.update("INSERT INTO users (id, status, created_at, updated_at) VALUES (?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", userId);
        jdbc.update("INSERT INTO documents (id, user_id, storage_key, filename, mime_type, size, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'test.pdf', 'application/pdf', 1, 'SUCCESS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                documentId, userId, "private/trusted/documents/transaction-test");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class QueueConfiguration {
        @Bean
        @Primary
        RecordingQueue documentDeletionQueuePort() { return new RecordingQueue(); }
    }

    static final class RecordingQueue implements DocumentDeletionQueuePort {
        private final List<DocumentDeletionMessage> messages = new ArrayList<>();

        @Override
        public void publish(DocumentDeletionMessage message) { messages.add(message); }
    }
}
