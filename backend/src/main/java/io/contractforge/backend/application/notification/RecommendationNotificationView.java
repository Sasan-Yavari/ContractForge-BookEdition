package io.contractforge.backend.application.notification;

import io.contractforge.backend.domain.model.MonitoringEventType;
import io.contractforge.backend.domain.model.RecommendationType;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Versioned, bounded semantic input for recommendation notification rendering.
 * No document, provider, recipient, or arbitrary JSON data crosses this boundary.
 */
public record RecommendationNotificationView(
        int schemaVersion,
        UUID recommendationId,
        RecommendationType recommendationType,
        MonitoringEventType eventType,
        OffsetDateTime eventDate,
        ImpactType impactType,
        ActionType actionType) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final DateTimeFormatter RFC3339_UTC = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    public RecommendationNotificationView {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported recommendation notification schema version: " + schemaVersion);
        }
        if (recommendationId == null || recommendationType == null || eventType == null || eventDate == null
                || impactType == null || actionType == null) {
            throw new IllegalArgumentException("Recommendation notification view is incomplete");
        }
        if (!ZoneOffset.UTC.equals(eventDate.getOffset())) {
            throw new IllegalArgumentException("Recommendation event date must use UTC");
        }
        if ((recommendationType == RecommendationType.CONTRACT_EXPIRY
                && eventType != MonitoringEventType.CONTRACT_EXPIRING)
                || (recommendationType == RecommendationType.RENEWAL
                && eventType != MonitoringEventType.CONTRACT_RENEWING)) {
            throw new IllegalArgumentException("Recommendation and event types do not match");
        }
        if (impactType != ImpactType.NONE || actionType != ActionType.REVIEW) {
            throw new IllegalArgumentException("Unsupported recommendation notification semantics");
        }
    }

    public String eventDateRfc3339() {
        return RFC3339_UTC.format(eventDate);
    }

    public enum ImpactType {
        NONE
    }

    public enum ActionType {
        REVIEW
    }
}
