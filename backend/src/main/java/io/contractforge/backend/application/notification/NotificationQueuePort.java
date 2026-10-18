package io.contractforge.backend.application.notification;

import java.util.UUID;
import io.contractforge.backend.domain.model.NotificationChannel;

/** Provider-neutral boundary for publishing a durable notification delivery intent. */
public interface NotificationQueuePort {
    void publish(NotificationDeliveryMessage message);

    record NotificationDeliveryMessage(UUID deliveryId, UUID notificationId, NotificationChannel channel) {
        public NotificationDeliveryMessage {
            if (deliveryId == null || notificationId == null || channel != NotificationChannel.EMAIL) {
                throw new IllegalArgumentException("Notification delivery message is incomplete");
            }
        }

    }
}
