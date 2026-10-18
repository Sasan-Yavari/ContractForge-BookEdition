package io.contractforge.backend.application.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.contractforge.backend.domain.model.PersistenceModels.Notification;
import io.contractforge.backend.domain.model.NotificationType;
import io.contractforge.backend.domain.model.MonitoringEventType;
import io.contractforge.backend.domain.model.RecommendationType;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Converts the persisted bounded semantic snapshot into the renderer's typed view. */
@Component
@Profile("!test")
public final class RecommendationNotificationViewFactory {
    private static final int MAX_SNAPSHOT_CHARACTERS = 2048;
    private final ObjectMapper objectMapper;

    public RecommendationNotificationViewFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RecommendationNotificationView create(Notification notification) {
        if (notification == null || notification.id() == null || notification.recommendationId() == null
                || notification.data() == null || notification.notificationType() != NotificationType.RECOMMENDATION) {
            throw new IllegalArgumentException("Notification rendering source is incomplete");
        }
        String json = notification.data().value();
        if (json.length() > MAX_SNAPSHOT_CHARACTERS) {
            throw new IllegalArgumentException("Notification rendering snapshot is too large");
        }
        try {
            JsonNode source = objectMapper.readTree(json);
            if (source == null || !source.isObject()) {
                throw new IllegalArgumentException("Notification rendering snapshot must be an object");
            }
            String reason = requiredText(source, "reason", "type");
            String event = requiredText(source, "evidence", "event_type");
            String eventDate = requiredText(source, "evidence", "event_date");
            String impact = requiredText(source, "impact", "type");
            String action = requiredText(source, "action", "type");
            if (!Set.of(RecommendationType.CONTRACT_EXPIRY.value(), RecommendationType.RENEWAL.value()).contains(reason)
                    || !Set.of(MonitoringEventType.CONTRACT_EXPIRING.value(), MonitoringEventType.CONTRACT_RENEWING.value()).contains(event)
                    || !Set.of(RecommendationNotificationView.ImpactType.NONE.name()).contains(impact)
                    || !Set.of(RecommendationNotificationView.ActionType.REVIEW.name()).contains(action)) {
                throw new IllegalArgumentException("Notification rendering snapshot contains an unsupported value");
            }
            return new RecommendationNotificationView(RecommendationNotificationView.CURRENT_SCHEMA_VERSION,
                    notification.recommendationId(),
                    RecommendationType.valueOf(reason), MonitoringEventType.valueOf(event), OffsetDateTime.parse(eventDate),
                    RecommendationNotificationView.ImpactType.valueOf(impact),
                    RecommendationNotificationView.ActionType.valueOf(action));
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new IllegalArgumentException("Notification rendering snapshot is invalid", exception);
        }
    }

    private String requiredText(JsonNode source, String object, String field) {
        JsonNode value = source.path(object).path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("Missing notification rendering field");
        }
        return value.textValue();
    }
}
