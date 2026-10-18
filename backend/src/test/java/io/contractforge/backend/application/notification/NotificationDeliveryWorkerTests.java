package io.contractforge.backend.application.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.port.RepositoryPorts.NotificationDeliveryRepository;
import io.contractforge.backend.domain.model.NotificationChannel;
import io.contractforge.backend.domain.model.NotificationDeliveryStatus;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import io.contractforge.backend.domain.model.PersistenceModels.Notification;
import io.contractforge.backend.domain.model.PersistenceModels.NotificationDelivery;
import io.contractforge.backend.domain.model.PersistenceModels.NotificationDeliveryWork;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationDeliveryWorkerTests {
    private static final UUID DELIVERY_ID = UUID.fromString("91000000-0000-0000-0000-000000000001");
    private static final UUID NOTIFICATION_ID = UUID.fromString("91000000-0000-0000-0000-000000000002");
    private static final UUID RECOMMENDATION_ID = UUID.fromString("91000000-0000-0000-0000-000000000003");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    @Test
    void duplicateDeliveryDoesNotSendAfterConfirmedDelivery() {
        Store store = store(NotificationDeliveryStatus.QUEUED, NOW.plusMinutes(10));
        List<EmailDeliveryPort.EmailMessage> sent = new ArrayList<>();
        NotificationDeliveryWorker worker = worker(store, sent::add, new NotificationDeliveryProperties(Duration.ofMinutes(5), 100));

        assertThat(worker.consume(message())).isEqualTo(NotificationDeliveryWorker.Outcome.DELIVERED);
        assertThat(worker.consume(message())).isEqualTo(NotificationDeliveryWorker.Outcome.ACKNOWLEDGED);

        assertThat(sent).hasSize(1);
        assertThat(store.delivery.status()).isEqualTo(NotificationDeliveryStatus.DELIVERED);
        assertThat(store.delivery.attemptCount()).isEqualTo(1);
    }

    @Test
    void expiredInFlightClaimIsRecoveredAndReclaimed() {
        Store store = store(NotificationDeliveryStatus.IN_FLIGHT, NOW.minusMinutes(1));
        store.delivery = withClaim(store.delivery, NOW.minusMinutes(10), NOW.minusMinutes(1), UUID.randomUUID(), 3);
        List<EmailDeliveryPort.EmailMessage> sent = new ArrayList<>();

        assertThat(worker(store, sent::add, new NotificationDeliveryProperties(Duration.ofMinutes(5), 100))
                .consume(message())).isEqualTo(NotificationDeliveryWorker.Outcome.DELIVERED);
        assertThat(store.delivery.attemptCount()).isEqualTo(4);
        assertThat(sent).hasSize(1);
    }

    @Test
    void staleWorkerCannotOverwriteNewerClaim() {
        Store store = store(NotificationDeliveryStatus.QUEUED, NOW.plusMinutes(10));
        NotificationDeliveryWorker worker = worker(store, ignored -> store.invalidateClaim(),
                new NotificationDeliveryProperties(Duration.ofMinutes(5), 100));

        assertThat(worker.consume(message())).isEqualTo(NotificationDeliveryWorker.Outcome.ACKNOWLEDGED);
        assertThat(store.delivery.status()).isEqualTo(NotificationDeliveryStatus.IN_FLIGHT);
        assertThat(store.delivery.claimToken()).isNotNull();
    }

    @Test
    void transientFailuresRetryThenConfiguredExhaustionUsesDlqOutcome() {
        Store store = store(NotificationDeliveryStatus.QUEUED, NOW.plusMinutes(10));
        NotificationDeliveryWorker worker = worker(store, ignored -> {
            throw new EmailDeliveryException(true, "EMAIL_PROVIDER_UNAVAILABLE");
        }, new NotificationDeliveryProperties(Duration.ofMinutes(5), 2));

        assertThat(worker.consume(message())).isEqualTo(NotificationDeliveryWorker.Outcome.RETRYABLE_FAILURE);
        assertThat(store.delivery.status()).isEqualTo(NotificationDeliveryStatus.QUEUED);
        assertThat(worker.consume(message())).isEqualTo(NotificationDeliveryWorker.Outcome.DEAD_LETTER);
        assertThat(store.delivery.status()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(store.delivery.failureCode()).isEqualTo("DELIVERY_ATTEMPTS_EXHAUSTED");
        assertThat(worker.consume(message())).isEqualTo(NotificationDeliveryWorker.Outcome.ACKNOWLEDGED);
    }

    @Test
    void missingRelationshipAndDisabledEmailAreAcknowledgedWithoutSending() {
        Store store = store(NotificationDeliveryStatus.QUEUED, NOW.plusMinutes(10));
        List<EmailDeliveryPort.EmailMessage> sent = new ArrayList<>();
        NotificationDeliveryWorker disabled = worker(store, sent::add, new NotificationDeliveryProperties(Duration.ofMinutes(5), 100), false);

        assertThat(disabled.consume(message())).isEqualTo(NotificationDeliveryWorker.Outcome.ACKNOWLEDGED);
        assertThat(sent).isEmpty();
        assertThat(store.delivery.status()).isEqualTo(NotificationDeliveryStatus.QUEUED);

        NotificationDeliveryWorker enabled = worker(store, sent::add, new NotificationDeliveryProperties(Duration.ofMinutes(5), 100));
        assertThat(enabled.consume(new NotificationQueuePort.NotificationDeliveryMessage(
                UUID.randomUUID(), NOTIFICATION_ID, NotificationChannel.EMAIL)))
                .isEqualTo(NotificationDeliveryWorker.Outcome.ACKNOWLEDGED);
        assertThat(sent).isEmpty();
    }

    @Test
    void invalidSemanticSnapshotFailsWithoutSesCall() {
        Store store = store(NotificationDeliveryStatus.QUEUED, NOW.plusMinutes(10));
        store.notification = new Notification(NOTIFICATION_ID, RECOMMENDATION_ID, "RECOMMENDATION",
                new JsonValue("{\"reason\":{\"type\":\"UNKNOWN\"}}"), NOW);
        List<EmailDeliveryPort.EmailMessage> sent = new ArrayList<>();

        assertThat(worker(store, sent::add, new NotificationDeliveryProperties(Duration.ofMinutes(5), 100))
                .consume(message())).isEqualTo(NotificationDeliveryWorker.Outcome.DEAD_LETTER);
        assertThat(sent).isEmpty();
        assertThat(store.delivery.failureCode()).isEqualTo("RENDERING_FAILED");
    }

    @Test
    void deletedSourceAfterClaimIsAcknowledgedWithoutSesCall() {
        Store store = store(NotificationDeliveryStatus.QUEUED, NOW.plusMinutes(10));
        store.invalidateBeforeSend = true;
        List<EmailDeliveryPort.EmailMessage> sent = new ArrayList<>();

        assertThat(worker(store, sent::add, new NotificationDeliveryProperties(Duration.ofMinutes(5), 100))
                .consume(message())).isEqualTo(NotificationDeliveryWorker.Outcome.ACKNOWLEDGED);
        assertThat(sent).isEmpty();
    }

    @Test
    void expiredLeaseAtFinalRevalidationIsAcknowledgedWithoutSesCall() {
        Store store = store(NotificationDeliveryStatus.QUEUED, NOW.plusMinutes(10));
        store.expireBeforeSend = true;
        List<EmailDeliveryPort.EmailMessage> sent = new ArrayList<>();

        assertThat(worker(store, sent::add, new NotificationDeliveryProperties(Duration.ofMinutes(5), 100))
                .consume(message())).isEqualTo(NotificationDeliveryWorker.Outcome.ACKNOWLEDGED);
        assertThat(sent).isEmpty();
    }

    private NotificationDeliveryWorker worker(Store store, EmailDeliveryPort email,
                                              NotificationDeliveryProperties properties) {
        return worker(store, email, properties, true);
    }

    private NotificationDeliveryWorker worker(Store store, EmailDeliveryPort email,
                                              NotificationDeliveryProperties properties, boolean enabled) {
        RecordingMetrics metrics = new RecordingMetrics();
        return new NotificationDeliveryWorker(store, new RecommendationNotificationViewFactory(new ObjectMapper()),
                new RepositoryEmailRenderer(), email, new NotificationEmailProperties(enabled), properties, metrics,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private NotificationQueuePort.NotificationDeliveryMessage message() {
        return new NotificationQueuePort.NotificationDeliveryMessage(DELIVERY_ID, NOTIFICATION_ID,
                NotificationChannel.EMAIL);
    }

    private Store store(NotificationDeliveryStatus status, OffsetDateTime leaseUntil) {
        OffsetDateTime created = NOW.minusMinutes(10);
        return new Store(new NotificationDelivery(DELIVERY_ID, NOTIFICATION_ID, NotificationChannel.EMAIL, status,
                "owner@example.test", 0, status == NotificationDeliveryStatus.IN_FLIGHT ? NOW.minusMinutes(2) : null,
                status == NotificationDeliveryStatus.IN_FLIGHT ? leaseUntil : null,
                status == NotificationDeliveryStatus.IN_FLIGHT ? UUID.randomUUID() : null,
                status == NotificationDeliveryStatus.IN_FLIGHT ? NOW.minusMinutes(2) : null, null, null, null,
                created, created), new Notification(NOTIFICATION_ID, RECOMMENDATION_ID, "RECOMMENDATION",
                new JsonValue("{\"reason\":{\"type\":\"CONTRACT_EXPIRY\"},\"evidence\":{\"event_type\":\"CONTRACT_EXPIRING\",\"event_date\":\"2026-01-02T00:00:00Z\"},\"impact\":{\"type\":\"NONE\"},\"action\":{\"type\":\"REVIEW\"}}"), NOW));
    }

    private NotificationDelivery withClaim(NotificationDelivery delivery, OffsetDateTime inFlightAt,
                                           OffsetDateTime leaseUntil, UUID token, int attempts) {
        return new NotificationDelivery(delivery.id(), delivery.notificationId(), delivery.channel(),
                NotificationDeliveryStatus.IN_FLIGHT, delivery.destination(), attempts, inFlightAt, leaseUntil, token,
                inFlightAt, null, null, null, delivery.createdAt(), delivery.updatedAt());
    }

    private static final class RecordingMetrics implements NotificationDeliveryMetrics {
        @Override public void record(DeliveryOutcome outcome) { }
    }

    private static final class Store implements NotificationDeliveryRepository {
        private NotificationDelivery delivery;
        private Notification notification;
        private boolean invalidateBeforeSend;
        private boolean expireBeforeSend;

        Store(NotificationDelivery delivery, Notification notification) {
            this.delivery = delivery;
            this.notification = notification;
        }

        @Override public int saveIfAbsent(NotificationDelivery value) { return 0; }
        @Override public Optional<NotificationDelivery> findById(UUID id) { return Optional.ofNullable(delivery); }
        @Override public List<NotificationDelivery> findQueued() { return List.of(); }

        @Override
        public Optional<NotificationDeliveryWork> findClaimed(UUID id, UUID token, OffsetDateTime now) {
            if (invalidateBeforeSend) {
                notification = null;
                delivery = new NotificationDelivery(delivery.id(), delivery.notificationId(), delivery.channel(),
                        NotificationDeliveryStatus.QUEUED, delivery.destination(), delivery.attemptCount(), null,
                        null, null, delivery.lastAttemptAt(), delivery.deliveredAt(), delivery.lastFailureAt(),
                        delivery.failureCode(), delivery.createdAt(), delivery.updatedAt());
                invalidateBeforeSend = false;
                return Optional.empty();
            }
            if (expireBeforeSend) {
                delivery = new NotificationDelivery(delivery.id(), delivery.notificationId(), delivery.channel(),
                        delivery.status(), delivery.destination(), delivery.attemptCount(), delivery.inFlightAt(),
                        now.minusSeconds(1), delivery.claimToken(), delivery.lastAttemptAt(), delivery.deliveredAt(),
                        delivery.lastFailureAt(), delivery.failureCode(), delivery.createdAt(), delivery.updatedAt());
                expireBeforeSend = false;
            }
            return matches(id, token) && delivery.leaseUntil().isAfter(now)
                    ? Optional.of(new NotificationDeliveryWork(delivery, notification))
                    : Optional.empty();
        }

        @Override
        public Optional<NotificationDeliveryWork> claim(UUID deliveryId, UUID notificationId,
                                                         OffsetDateTime claimedAt, OffsetDateTime leaseUntil,
                                                         UUID claimToken) {
            if (!delivery.id().equals(deliveryId) || !delivery.notificationId().equals(notificationId)
                    || delivery.status() != NotificationDeliveryStatus.QUEUED) return Optional.empty();
            delivery = new NotificationDelivery(delivery.id(), delivery.notificationId(), delivery.channel(),
                    NotificationDeliveryStatus.IN_FLIGHT, delivery.destination(), delivery.attemptCount() + 1,
                    claimedAt, leaseUntil, claimToken, claimedAt, delivery.deliveredAt(), delivery.lastFailureAt(),
                    delivery.failureCode(), delivery.createdAt(), claimedAt);
            return Optional.of(new NotificationDeliveryWork(delivery, notification));
        }

        @Override public int recoverExpiredClaims(OffsetDateTime now) {
            if (delivery.status() == NotificationDeliveryStatus.IN_FLIGHT && delivery.leaseUntil() != null
                    && !delivery.leaseUntil().isAfter(now)) {
                delivery = new NotificationDelivery(delivery.id(), delivery.notificationId(), delivery.channel(),
                        NotificationDeliveryStatus.QUEUED, delivery.destination(), delivery.attemptCount(), null, null,
                        null, delivery.lastAttemptAt(), delivery.deliveredAt(), delivery.lastFailureAt(),
                        delivery.failureCode(), delivery.createdAt(), now);
                return 1;
            }
            return 0;
        }

        @Override public int markDelivered(UUID id, UUID token, OffsetDateTime at) {
            if (!matches(id, token)) return 0;
            delivery = new NotificationDelivery(delivery.id(), delivery.notificationId(), delivery.channel(),
                    NotificationDeliveryStatus.DELIVERED, delivery.destination(), delivery.attemptCount(), null, null,
                    null, delivery.lastAttemptAt(), at, delivery.lastFailureAt(), delivery.failureCode(),
                    delivery.createdAt(), at);
            return 1;
        }

        @Override public int returnToQueued(UUID id, UUID token, OffsetDateTime at, String code) {
            if (!matches(id, token)) return 0;
            delivery = state(NotificationDeliveryStatus.QUEUED, at, code);
            return 1;
        }

        @Override public int markFailed(UUID id, UUID token, OffsetDateTime at, String code) {
            if (!matches(id, token)) return 0;
            delivery = state(NotificationDeliveryStatus.FAILED, at, code);
            return 1;
        }

        void invalidateClaim() {
            delivery = new NotificationDelivery(delivery.id(), delivery.notificationId(), delivery.channel(),
                    delivery.status(), delivery.destination(), delivery.attemptCount(), delivery.inFlightAt(),
                    delivery.leaseUntil(), UUID.randomUUID(), delivery.lastAttemptAt(), delivery.deliveredAt(),
                    delivery.lastFailureAt(), delivery.failureCode(), delivery.createdAt(), delivery.updatedAt());
        }

        private boolean matches(UUID id, UUID token) {
            return delivery.id().equals(id) && delivery.claimToken().equals(token)
                    && delivery.status() == NotificationDeliveryStatus.IN_FLIGHT;
        }

        private NotificationDelivery state(NotificationDeliveryStatus status, OffsetDateTime at, String code) {
            return new NotificationDelivery(delivery.id(), delivery.notificationId(), delivery.channel(), status,
                    delivery.destination(), delivery.attemptCount(), null, null, null, delivery.lastAttemptAt(),
                    delivery.deliveredAt(), at, code, delivery.createdAt(), at);
        }
    }
}
