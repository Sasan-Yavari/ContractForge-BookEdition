package io.contractforge.backend.application.notification;

import io.contractforge.backend.application.port.RepositoryPorts.NotificationDeliveryRepository;
import io.contractforge.backend.domain.model.PersistenceModels.NotificationDelivery;
import io.contractforge.backend.domain.model.NotificationChannel;
import io.contractforge.backend.domain.model.NotificationDeliveryStatus;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publishes durable delivery intents only after their owning transaction commits. */
@Service
@Profile("!test")
public class NotificationDispatchService {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);
    private final NotificationDeliveryRepository deliveries;
    private final NotificationQueuePort queue;
    private final NotificationEmailProperties properties;
    private final NotificationEnvelopeSerializer envelopeSerializer;

    public NotificationDispatchService(NotificationDeliveryRepository deliveries, NotificationQueuePort queue,
                                        NotificationEmailProperties properties, NotificationEnvelopeSerializer envelopeSerializer) {
        this.deliveries = deliveries;
        this.queue = queue;
        this.properties = properties;
        this.envelopeSerializer = envelopeSerializer;
    }

    public void publishAfterCommit(UUID deliveryId) {
        if (!properties.enabled()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Notification delivery publication requires an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishSafely(deliveryId);
            }
        });
    }

    /** Republishes all currently durable, eligible intents after a prior publication failure. */
    public int recoverQueued() {
        if (!properties.enabled()) {
            return 0;
        }
        List<NotificationDelivery> queued = deliveries.findQueued();
        queued.stream().filter(this::eligible)
                .forEach(delivery -> publishSafely(delivery.id(), delivery.notificationId(), delivery.channel()));
        return queued.size();
    }

    private void publishSafely(UUID deliveryId) {
        try {
            deliveries.findById(deliveryId).filter(this::eligible).ifPresent(delivery ->
                    publishSafely(delivery.id(), delivery.notificationId(), delivery.channel()));
        } catch (RuntimeException exception) {
            log.atWarn()
                    .addKeyValue("event_type", "notification_delivery_publication_failed")
                    .addKeyValue("delivery_id", deliveryId)
                    .addKeyValue("failure_code", failureCode(exception))
                    .log("notification_delivery_publication_failed");
        }
    }

    private boolean eligible(NotificationDelivery delivery) {
        return delivery.status() == NotificationDeliveryStatus.QUEUED && delivery.channel() == NotificationChannel.EMAIL
                && delivery.destination() != null && !delivery.destination().isBlank();
    }

    private void publishSafely(UUID deliveryId, UUID notificationId, NotificationChannel channel) {
        try {
            queue.publish(new NotificationQueuePort.NotificationDeliveryMessage(deliveryId, notificationId, channel));
        } catch (RuntimeException exception) {
            log.atWarn()
                    .addKeyValue("event_type", "notification_delivery_publication_failed")
                    .addKeyValue("delivery_id", deliveryId)
                    .addKeyValue("failure_code", failureCode(exception))
                    .log("notification_delivery_publication_failed");
        }
    }

    public String envelope(NotificationQueuePort.NotificationDeliveryMessage message) {
        return envelopeSerializer.serialize(message);
    }

    private String failureCode(RuntimeException exception) {
        String name = exception.getClass().getSimpleName();
        return name.length() <= 100 ? name : name.substring(0, 100);
    }
}
