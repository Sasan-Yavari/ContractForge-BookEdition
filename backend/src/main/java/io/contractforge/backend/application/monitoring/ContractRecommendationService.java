package io.contractforge.backend.application.monitoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.domain.model.PersistenceModels.Event;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import io.contractforge.backend.domain.model.PersistenceModels.Recommendation;
import io.contractforge.backend.domain.model.RecommendationType;
import io.contractforge.backend.application.notification.RecommendationNotificationView;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Derives structured, presentation-neutral recommendations from authoritative monitoring Events. */
@Service
@Profile("!test")
public class ContractRecommendationService {
    private final ObjectMapper json;

    public ContractRecommendationService(ObjectMapper json) {
        this.json = json;
    }

    public Optional<Recommendation> from(Event event) {
        if (event == null || event.id() == null || event.userId() == null || event.documentId() == null
                || event.type() == null || event.eventDate() == null || event.sourceReference() == null
                || event.createdAt() == null) {
            return Optional.empty();
        }
        return switch (event.type()) {
            case CONTRACT_EXPIRING -> Optional.of(create(event, RecommendationType.CONTRACT_EXPIRY));
            case CONTRACT_RENEWING -> Optional.of(create(event, RecommendationType.RENEWAL));
            default -> Optional.empty();
        };
    }

    private Recommendation create(Event event, RecommendationType recommendationType) {
        UUID recommendationId = UUID.nameUUIDFromBytes(("monitoring-recommendation:" + event.id())
                .getBytes(StandardCharsets.UTF_8));
        try {
            String data = json.writeValueAsString(new RecommendationData(
                    new TypeValue(recommendationType.value()),
                    new Evidence(event.type().value(), event.eventDate().toString(), event.sourceReference()),
                    new TypeValue(RecommendationNotificationView.ImpactType.NONE.name()),
                    new TypeValue(RecommendationNotificationView.ActionType.REVIEW.name())));
            return new Recommendation(recommendationId, event.userId(), event.documentId(), recommendationType,
                    new JsonValue(data), event.createdAt(), null);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Recommendation data could not be serialized", failure);
        }
    }

    private record RecommendationData(TypeValue reason, Evidence evidence, TypeValue impact, TypeValue action) { }
    private record TypeValue(String type) { }
    private record Evidence(String event_type, String event_date, String source_reference) { }
}
