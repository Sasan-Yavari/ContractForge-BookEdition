package io.contractforge.backend.application.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** Serializes the versioned, data-minimized notification queue contract. */
@Component
public class NotificationEnvelopeSerializer {
    private final ObjectMapper objectMapper;

    public NotificationEnvelopeSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(NotificationQueuePort.NotificationDeliveryMessage message) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("schemaVersion", NotificationQueueProtocol.SCHEMA_VERSION);
        envelope.put("messageType", NotificationQueueProtocol.MESSAGE_TYPE_DELIVERY);
        envelope.put("deliveryId", message.deliveryId().toString());
        envelope.put("notificationId", message.notificationId().toString());
        envelope.put("channel", message.channel().name());
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Notification delivery envelope could not be serialized", exception);
        }
    }
}
