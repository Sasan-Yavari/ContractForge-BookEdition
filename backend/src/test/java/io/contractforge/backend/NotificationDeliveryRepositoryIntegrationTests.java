package io.contractforge.backend;

import static org.assertj.core.api.Assertions.assertThat;

import io.contractforge.backend.application.port.RepositoryPorts;
import io.contractforge.backend.domain.model.NotificationChannel;
import io.contractforge.backend.domain.model.NotificationDeliveryStatus;
import io.contractforge.backend.domain.model.PersistenceModels.NotificationDeliveryWork;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationDeliveryRepositoryIntegrationTests extends PostgresIntegrationTest {
    private final UUID userId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID recommendationId = UUID.randomUUID();
    private final UUID notificationId = UUID.randomUUID();
    private final UUID deliveryId = UUID.randomUUID();
    private final OffsetDateTime createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RepositoryPorts.NotificationDeliveryRepository deliveries;

    @BeforeEach
    void insertDelivery() {
        jdbc.update("INSERT INTO users (id, status, created_at, updated_at) VALUES (?, 'ACTIVE', ?, ?)",
                userId, createdAt, createdAt);
        jdbc.update("INSERT INTO documents (id, user_id, storage_key, filename, mime_type, size, status, created_at, updated_at) "
                + "VALUES (?, ?, 'private/test', 'test.pdf', 'application/pdf', 1, 'SUCCESS', ?, ?)",
                documentId, userId, createdAt, createdAt);
        jdbc.update("INSERT INTO recommendations (id, user_id, document_id, type, data, created_at) VALUES (?, ?, ?, 'RENEWAL', ?::jsonb, ?)",
                recommendationId, userId, documentId, "{}", createdAt);
        jdbc.update("INSERT INTO notifications (id, recommendation_id, notification_type, data, created_at) VALUES (?, ?, 'RECOMMENDATION', ?::jsonb, ?)",
                notificationId, recommendationId, "{}", createdAt);
        jdbc.update("INSERT INTO notification_deliveries (id, notification_id, channel, status, destination, created_at, updated_at) "
                + "VALUES (?, ?, 'EMAIL', 'QUEUED', 'owner@example.test', ?, ?)", deliveryId, notificationId, createdAt, createdAt);
    }

    @AfterEach
    void deleteData() {
        jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void concurrentClaimsConvergeToOneInFlightAttemptAndGuardStaleUpdates() throws Exception {
        OffsetDateTime now = createdAt.plus(1, ChronoUnit.HOURS);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<java.util.Optional<NotificationDeliveryWork>> first = executor.submit(() -> {
                start.await();
                return deliveries.claim(deliveryId, notificationId, now, now.plusMinutes(5), UUID.randomUUID());
            });
            Future<java.util.Optional<NotificationDeliveryWork>> second = executor.submit(() -> {
                start.await();
                return deliveries.claim(deliveryId, notificationId, now, now.plusMinutes(5), UUID.randomUUID());
            });
            start.countDown();

            List<java.util.Optional<NotificationDeliveryWork>> results = List.of(first.get(), second.get());
            assertThat(results).filteredOn(java.util.Optional::isPresent).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT status FROM notification_deliveries WHERE id = ?", String.class, deliveryId))
                    .isEqualTo(NotificationDeliveryStatus.IN_FLIGHT.name());
            assertThat(jdbc.queryForObject("SELECT attempt_count FROM notification_deliveries WHERE id = ?", Integer.class, deliveryId))
                    .isEqualTo(1);

            NotificationDeliveryWork winner = results.stream().flatMap(java.util.Optional::stream).findFirst().orElseThrow();
            assertThat(deliveries.markDelivered(deliveryId, UUID.randomUUID(), now)).isZero();
            assertThat(deliveries.markDelivered(deliveryId, winner.delivery().claimToken(), now)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredInFlightClaimReturnsToQueueBeforeReclaim() {
        OffsetDateTime expired = createdAt.plus(1, ChronoUnit.HOURS);
        jdbc.update("UPDATE notification_deliveries SET status = 'IN_FLIGHT', in_flight_at = ?, lease_until = ?, "
                        + "claim_token = ?, attempt_count = 1 WHERE id = ?", expired.minusMinutes(10), expired.minusMinutes(1),
                UUID.randomUUID(), deliveryId);

        assertThat(deliveries.recoverExpiredClaims(expired)).isEqualTo(1);
        assertThat(deliveries.claim(deliveryId, notificationId, expired, expired.plusMinutes(5), UUID.randomUUID()))
                .isPresent().get().extracting(work -> work.delivery().status())
                .isEqualTo(NotificationDeliveryStatus.IN_FLIGHT);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM notification_deliveries WHERE id = ?", Integer.class, deliveryId))
                .isEqualTo(2);
    }
}
