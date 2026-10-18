package io.contractforge.backend.infrastructure.notification;

import io.contractforge.backend.application.notification.NotificationEnvelopeSerializer;
import io.contractforge.backend.application.notification.NotificationQueuePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** AWS transport adapter; queue provisioning and naming remain deployment configuration. */
@Component
@Profile("!test")
final class SqsNotificationQueueAdapter implements NotificationQueuePort {
    private final SqsClient sqs;
    private final NotificationQueueProperties properties;
    private final NotificationEnvelopeSerializer envelopeSerializer;

    SqsNotificationQueueAdapter(SqsClient sqs, NotificationQueueProperties properties,
                                NotificationEnvelopeSerializer envelopeSerializer) {
        this.sqs = sqs;
        this.properties = properties;
        this.envelopeSerializer = envelopeSerializer;
    }

    @Override
    public void publish(NotificationDeliveryMessage message) {
        if (properties.queueUrl() == null || properties.queueUrl().isBlank()) {
            throw new IllegalStateException("Notification queue is not configured");
        }
        try {
            sqs.sendMessage(SendMessageRequest.builder().queueUrl(properties.queueUrl())
                    .messageBody(envelopeSerializer.serialize(message)).build());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Notification queue publication failed", exception);
        }
    }
}
