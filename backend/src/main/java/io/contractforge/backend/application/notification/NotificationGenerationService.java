package io.contractforge.backend.application.notification;

import io.contractforge.backend.application.identity.TrustedEmailAddressNormalizer;
import io.contractforge.backend.application.port.RepositoryPorts.NotificationDeliveryRepository;
import io.contractforge.backend.application.port.RepositoryPorts.NotificationRepository;
import io.contractforge.backend.application.port.RepositoryPorts.UserIdentityRepository;
import io.contractforge.backend.domain.model.PersistenceModels.Notification;
import io.contractforge.backend.domain.model.PersistenceModels.NotificationDelivery;
import io.contractforge.backend.domain.model.PersistenceModels.Recommendation;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import io.contractforge.backend.domain.model.NotificationChannel;
import io.contractforge.backend.domain.model.NotificationDeliveryStatus;
import io.contractforge.backend.domain.model.NotificationFailureCode;
import io.contractforge.backend.domain.model.NotificationType;
import io.contractforge.backend.domain.model.MonitoringEventType;
import io.contractforge.backend.domain.model.RecommendationType;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists the semantic Notification and EMAIL delivery intent for one Recommendation. */
@Service
@Profile("!test")
public class NotificationGenerationService implements NotificationGenerationPort {

    private final NotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final UserIdentityRepository identities;
    private final ObjectMapper objectMapper;
    private final NotificationDispatchService dispatch;

    public NotificationGenerationService(NotificationRepository notifications,
                                         NotificationDeliveryRepository deliveries,
                                         @Qualifier("userIdentityRepository") UserIdentityRepository identities,
                                         ObjectMapper objectMapper, NotificationDispatchService dispatch) {
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.identities = identities;
        this.objectMapper = objectMapper;
        this.dispatch = dispatch;
    }

    @Override
    @Transactional
    public void generate(Recommendation recommendation) {
        if (recommendation == null || recommendation.id() == null || recommendation.userId() == null
                || recommendation.data() == null || recommendation.createdAt() == null) {
            throw new IllegalArgumentException("Recommendation notification source is incomplete");
        }

        Notification notification = new Notification(notificationId(recommendation.id()), recommendation.id(),
                NotificationType.RECOMMENDATION, boundedSemanticSnapshot(recommendation), recommendation.createdAt());
        notifications.saveIfAbsent(notification);
        Notification persisted = notifications.findByRecommendationId(recommendation.id()).orElseThrow(
                () -> new IllegalStateException("Notification was not persisted for Recommendation"));

        OffsetDateTime now = OffsetDateTime.now();
        String destination = identities.findTrustedEmailByUserId(recommendation.userId())
                .flatMap(email -> TrustedEmailAddressNormalizer.normalize(email, true))
                .orElse(null);
        boolean destinationAvailable = destination != null;
        NotificationDelivery delivery = new NotificationDelivery(deliveryId(persisted.id()), persisted.id(), NotificationChannel.EMAIL,
                destinationAvailable ? NotificationDeliveryStatus.QUEUED : NotificationDeliveryStatus.FAILED, destination, 0, null, null, null, null,
                null, destinationAvailable ? null : now,
                destinationAvailable ? null : NotificationFailureCode.RECIPIENT_UNAVAILABLE.name(),
                now, now);
        deliveries.saveIfAbsent(delivery);
        dispatch.publishAfterCommit(delivery.id());
    }

    private io.contractforge.backend.domain.model.PersistenceModels.JsonValue boundedSemanticSnapshot(
            Recommendation recommendation) {
        try {
            JsonNode source = objectMapper.readTree(recommendation.data().value());
            if (source == null || !source.isObject()) {
                throw new IllegalArgumentException("Recommendation notification data must be an object");
            }
            String reasonType = requiredText(source, "reason", "type");
            String eventType = requiredText(source, "evidence", "event_type");
            String eventDate = requiredText(source, "evidence", "event_date");
            String impactType = requiredText(source, "impact", "type");
            String actionType = requiredText(source, "action", "type");
            if (!Set.of(RecommendationType.CONTRACT_EXPIRY.value(), RecommendationType.RENEWAL.value()).contains(reasonType)
                    || !Set.of(MonitoringEventType.CONTRACT_EXPIRING.value(), MonitoringEventType.CONTRACT_RENEWING.value()).contains(eventType)
                    || !Set.of(RecommendationNotificationView.ImpactType.NONE.name()).contains(impactType)
                    || !Set.of(RecommendationNotificationView.ActionType.REVIEW.name()).contains(actionType)) {
                throw new IllegalArgumentException("Recommendation notification data contains an unsupported value");
            }
            OffsetDateTime.parse(eventDate);
            ObjectNode bounded = objectMapper.createObjectNode();
            bounded.putObject("reason").put("type", reasonType);
            bounded.putObject("evidence").put("event_type", eventType).put("event_date", eventDate);
            bounded.putObject("impact").put("type", impactType);
            bounded.putObject("action").put("type", actionType);
            return new io.contractforge.backend.domain.model.PersistenceModels.JsonValue(
                    objectMapper.writeValueAsString(bounded));
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new IllegalArgumentException("Recommendation notification data is not a bounded semantic snapshot",
                    exception);
        }
    }

    private String requiredText(JsonNode source, String object, String field) {
        JsonNode value = source.path(object).path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Missing recommendation notification field: " + object + "." + field);
        }
        return value.textValue();
    }

    private UUID notificationId(UUID recommendationId) {
        return UUID.nameUUIDFromBytes(("contractforge-notification:" + recommendationId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private UUID deliveryId(UUID notificationId) {
        return UUID.nameUUIDFromBytes(("contractforge-notification-delivery:" + NotificationChannel.EMAIL.name() + ":" + notificationId)
                .getBytes(StandardCharsets.UTF_8));
    }
}
