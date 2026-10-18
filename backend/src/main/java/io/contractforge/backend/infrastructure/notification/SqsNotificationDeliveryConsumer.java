package io.contractforge.backend.infrastructure.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.contractforge.backend.application.notification.NotificationDeliveryWorker;
import io.contractforge.backend.application.notification.NotificationQueueProtocol;
import io.contractforge.backend.application.notification.NotificationQueuePort;
import io.contractforge.backend.domain.model.NotificationChannel;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** SQS runtime adapter for delivery work and the dedicated notification DLQ. */
@Component
@Profile("!test")
public final class SqsNotificationDeliveryConsumer {
    private final NotificationDeliveryWorker worker;
    private final SqsClient sqs;
    private final NotificationQueueProperties properties;
    private final ObjectMapper objectMapper;

    public SqsNotificationDeliveryConsumer(NotificationDeliveryWorker worker, SqsClient sqs,
                                           NotificationQueueProperties properties, ObjectMapper objectMapper) {
        this.worker = worker;
        this.sqs = sqs;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${contractforge.notification.queue.poll-seconds:5}000")
    public void poll() {
        if (blank(properties.queueUrl())) {
            return;
        }
        sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(properties.queueUrl()).maxNumberOfMessages(1)
                .waitTimeSeconds(Math.max(1, Math.min(20, properties.pollSeconds()))).build()).messages().forEach(this::handle);
    }

    public NotificationDeliveryWorker.Outcome consume(String body) {
        return worker.consume(decode(body));
    }

    private void handle(Message message) {
        NotificationQueuePort.NotificationDeliveryMessage decoded;
        try {
            decoded = decode(message.body());
        } catch (RuntimeException malformed) {
            deadLetter(message);
            return;
        }
        NotificationDeliveryWorker.Outcome outcome = worker.consume(decoded);
        if (outcome == NotificationDeliveryWorker.Outcome.DEAD_LETTER) {
            deadLetter(message);
            return;
        }
        if (outcome == NotificationDeliveryWorker.Outcome.RETRYABLE_FAILURE) {
            return;
        }
        delete(message.receiptHandle());
    }

    private NotificationQueuePort.NotificationDeliveryMessage decode(String body) {
        try {
            JsonNode value = objectMapper.readTree(body);
            if (value == null || !value.isObject() || value.size() != 5
                    || !value.path("schemaVersion").isIntegralNumber()
                    || value.path("schemaVersion").intValue() != NotificationQueueProtocol.SCHEMA_VERSION
                    || !NotificationQueueProtocol.MESSAGE_TYPE_DELIVERY.equals(value.path("messageType").textValue())) {
                throw new IllegalArgumentException("Unsupported notification delivery envelope");
            }
            return new NotificationQueuePort.NotificationDeliveryMessage(
                    UUID.fromString(required(value, "deliveryId")), UUID.fromString(required(value, "notificationId")),
                    NotificationChannel.fromValue(required(value, "channel")));
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new IllegalArgumentException("Malformed notification delivery envelope", failure);
        }
    }

    private String required(JsonNode value, String field) {
        String text = value.path(field).textValue();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Missing notification delivery envelope field");
        }
        return text;
    }

    private void deadLetter(Message message) {
        if (blank(properties.dlqUrl())) {
            throw new IllegalStateException("Notification delivery DLQ is not configured");
        }
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(properties.dlqUrl()).messageBody(message.body()).build());
        delete(message.receiptHandle());
    }

    private void delete(String receiptHandle) {
        sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(properties.queueUrl())
                .receiptHandle(receiptHandle).build());
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
