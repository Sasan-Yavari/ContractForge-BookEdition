package io.contractforge.backend.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.notification.EmailDeliveryPort;
import io.contractforge.backend.application.notification.EmailDeliveryException;
import io.contractforge.backend.application.notification.EmailRenderer;
import io.contractforge.backend.application.notification.NotificationDeliveryMetrics;
import io.contractforge.backend.application.notification.NotificationDeliveryProperties;
import io.contractforge.backend.application.notification.NotificationDeliveryWorker;
import io.contractforge.backend.application.notification.NotificationQueueProtocol;
import io.contractforge.backend.application.notification.NotificationEmailProperties;
import io.contractforge.backend.application.notification.NotificationQueuePort;
import io.contractforge.backend.application.notification.RecommendationNotificationViewFactory;
import io.contractforge.backend.application.notification.RepositoryEmailRenderer;
import io.contractforge.backend.application.port.RepositoryPorts.NotificationDeliveryRepository;
import io.contractforge.backend.domain.model.NotificationChannel;
import io.contractforge.backend.domain.model.NotificationDeliveryStatus;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import io.contractforge.backend.domain.model.PersistenceModels.Notification;
import io.contractforge.backend.domain.model.PersistenceModels.NotificationDelivery;
import io.contractforge.backend.domain.model.PersistenceModels.NotificationDeliveryWork;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

class SqsNotificationDeliveryConsumerTests {
    private static final UUID DELIVERY_ID = UUID.fromString("92000000-0000-0000-0000-000000000001");
    private static final UUID NOTIFICATION_ID = UUID.fromString("92000000-0000-0000-0000-000000000002");
    private static final UUID RECOMMENDATION_ID = UUID.fromString("92000000-0000-0000-0000-000000000003");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    @Test
    void malformedEnvelopeIsTransferredToDedicatedDlqAndDeleted() {
        Calls calls = calls(String.format("{\"schemaVersion\":%d}", NotificationQueueProtocol.SCHEMA_VERSION));
        consumer(calls.sqs(), "dlq-url", validNotification()).poll();

        assertThat(calls.sentDlq).isEqualTo(String.format("{\"schemaVersion\":%d}", NotificationQueueProtocol.SCHEMA_VERSION));
        assertThat(calls.deleted).isTrue();
    }

    @Test
    void successfulWorkerOutcomeAcknowledgesSourceQueueMessage() {
        Calls calls = calls(envelope());
        consumer(calls.sqs(), "dlq-url", validNotification()).poll();

        assertThat(calls.sentDlq).isNull();
        assertThat(calls.deleted).isTrue();
    }

    @Test
    void terminalWorkerOutcomeIsTransferredToDedicatedDlq() {
        Calls calls = calls(envelope());
        consumer(calls.sqs(), "dlq-url", invalidNotification()).poll();

        assertThat(calls.sentDlq).isEqualTo(envelope());
        assertThat(calls.deleted).isTrue();
    }

    @Test
    void retryableWorkerOutcomeLeavesSourceVisibleForQueueRedelivery() {
        Calls calls = calls(envelope());
        consumer(calls.sqs(), "dlq-url", validNotification(), true).poll();

        assertThat(calls.sentDlq).isNull();
        assertThat(calls.deleted).isFalse();
    }

    @Test
    void malformedEnvelopeFailsClosedWhenDlqIsNotConfigured() {
        Calls calls = calls("not-json");

        assertThatThrownBy(() -> consumer(calls.sqs(), "", validNotification()).poll())
                .isInstanceOf(IllegalStateException.class);
        assertThat(calls.deleted).isFalse();
    }

    private SqsNotificationDeliveryConsumer consumer(SqsClient sqs, String dlqUrl, Notification notification) {
        return consumer(sqs, dlqUrl, notification, false);
    }

    private SqsNotificationDeliveryConsumer consumer(SqsClient sqs, String dlqUrl, Notification notification,
                                                     boolean transientFailure) {
        NotificationDeliveryRepository repository = new Store(notification);
        NotificationDeliveryWorker worker = new NotificationDeliveryWorker(repository,
                new RecommendationNotificationViewFactory(new ObjectMapper()), new RepositoryEmailRenderer(),
                email -> { if (transientFailure) throw new EmailDeliveryException(true, "EMAIL_PROVIDER_UNAVAILABLE"); },
                new NotificationEmailProperties(true),
                new NotificationDeliveryProperties(Duration.ofMinutes(5), 3), outcome -> { },
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        return new SqsNotificationDeliveryConsumer(worker, sqs,
                new NotificationQueueProperties("queue-url", dlqUrl, 5), new ObjectMapper());
    }

    private Calls calls(String body) {
        Calls calls = new Calls(body);
        calls.client = (SqsClient) Proxy.newProxyInstance(SqsClient.class.getClassLoader(),
                new Class<?>[] {SqsClient.class}, (proxy, method, args) -> {
                    if (method.getName().equals("receiveMessage")) return calls.receive();
                    if (method.getName().equals("sendMessage")) {
                        calls.sentDlq = ((SendMessageRequest) args[0]).messageBody();
                        return SendMessageResponse.builder().messageId("dlq-id").build();
                    }
                    if (method.getName().equals("deleteMessage")) {
                        calls.deleted = true;
                        DeleteMessageRequest request = (DeleteMessageRequest) args[0];
                        assertThat(request.queueUrl()).isEqualTo("queue-url");
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType().isPrimitive()) return 0;
                    return null;
                });
        return calls;
    }

    private String envelope() {
        return "{\"schemaVersion\":" + NotificationQueueProtocol.SCHEMA_VERSION
                + ",\"messageType\":\"" + NotificationQueueProtocol.MESSAGE_TYPE_DELIVERY + "\""
                + ","
                + "\"deliveryId\":\"92000000-0000-0000-0000-000000000001\","
                + "\"notificationId\":\"92000000-0000-0000-0000-000000000002\",\"channel\":\"EMAIL\"}";
    }

    private Notification validNotification() {
        return new Notification(NOTIFICATION_ID, RECOMMENDATION_ID, "RECOMMENDATION", new JsonValue(
                "{\"reason\":{\"type\":\"CONTRACT_EXPIRY\"},\"evidence\":{\"event_type\":\"CONTRACT_EXPIRING\","
                        + "\"event_date\":\"2026-01-02T00:00:00Z\"},\"impact\":{\"type\":\"NONE\"},"
                        + "\"action\":{\"type\":\"REVIEW\"}}"), NOW);
    }

    private Notification invalidNotification() {
        return new Notification(NOTIFICATION_ID, RECOMMENDATION_ID, "RECOMMENDATION",
                new JsonValue("{\"invalid\":true}"), NOW);
    }

    private static final class Calls {
        private final String body;
        private SqsClient client;
        private String sentDlq;
        private boolean deleted;

        Calls(String body) { this.body = body; }
        SqsClient sqs() { return client; }
        ReceiveMessageResponse receive() {
            return ReceiveMessageResponse.builder().messages(Message.builder().body(body).receiptHandle("receipt").build()).build();
        }
    }

    private static final class Store implements NotificationDeliveryRepository {
        private NotificationDelivery delivery = new NotificationDelivery(DELIVERY_ID, NOTIFICATION_ID,
                NotificationChannel.EMAIL, NotificationDeliveryStatus.QUEUED, "owner@example.test", 0, null, null,
                null, null, null, null, null, NOW.minusMinutes(10), NOW.minusMinutes(10));
        private final Notification notification;

        Store(Notification notification) { this.notification = notification; }
        @Override public int saveIfAbsent(NotificationDelivery value) { return 0; }
        @Override public Optional<NotificationDelivery> findById(UUID id) { return Optional.of(delivery); }
        @Override public List<NotificationDelivery> findQueued() { return List.of(); }
        @Override public Optional<NotificationDeliveryWork> claim(UUID id, UUID notificationId, OffsetDateTime at,
                                                                   OffsetDateTime lease, UUID token) {
            if (!delivery.id().equals(id) || !delivery.notificationId().equals(notificationId)
                    || delivery.status() != NotificationDeliveryStatus.QUEUED) return Optional.empty();
            delivery = new NotificationDelivery(delivery.id(), delivery.notificationId(), delivery.channel(),
                    NotificationDeliveryStatus.IN_FLIGHT, delivery.destination(), 1, at, lease, token, at, null,
                    null, null, delivery.createdAt(), at);
            return Optional.of(new NotificationDeliveryWork(delivery, notification));
        }
        @Override public Optional<NotificationDeliveryWork> findClaimed(UUID id, UUID token, OffsetDateTime now) {
            return delivery.status() == NotificationDeliveryStatus.IN_FLIGHT && delivery.claimToken().equals(token)
                    ? Optional.of(new NotificationDeliveryWork(delivery, notification)) : Optional.empty();
        }
        @Override public int recoverExpiredClaims(OffsetDateTime at) { return 0; }
        @Override public int markDelivered(UUID id, UUID token, OffsetDateTime at) { delivery = state(NotificationDeliveryStatus.DELIVERED); return 1; }
        @Override public int returnToQueued(UUID id, UUID token, OffsetDateTime at, String code) {
            delivery = state(NotificationDeliveryStatus.QUEUED);
            return 1;
        }
        @Override public int markFailed(UUID id, UUID token, OffsetDateTime at, String code) { delivery = state(NotificationDeliveryStatus.FAILED); return 1; }
        private NotificationDelivery state(NotificationDeliveryStatus status) {
            return new NotificationDelivery(delivery.id(), delivery.notificationId(), delivery.channel(), status,
                    delivery.destination(), delivery.attemptCount(), null, null, null, delivery.lastAttemptAt(),
                    status == NotificationDeliveryStatus.DELIVERED ? NOW : null, NOW, null, delivery.createdAt(), NOW);
        }
    }
}
