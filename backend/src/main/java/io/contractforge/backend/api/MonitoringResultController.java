package io.contractforge.backend.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.contractforge.backend.application.identity.IdentityResolutionService;
import io.contractforge.backend.application.monitoring.MonitoringResultQueryService;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import io.contractforge.backend.domain.model.PersistenceModels.Event;
import io.contractforge.backend.domain.model.PersistenceModels.Recommendation;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@Profile("!test")
public class MonitoringResultController {

    private final MonitoringResultQueryService results;
    private final IdentityResolutionService identities;
    private final ObjectMapper json;

    public MonitoringResultController(MonitoringResultQueryService results, IdentityResolutionService identities,
                                      ObjectMapper json) {
        this.results = results;
        this.identities = identities;
        this.json = json;
    }

    @GetMapping("/events")
    public List<EventResponse> events(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return results.events(identities.resolve(principal)).stream().map(EventResponse::from).toList();
    }

    @GetMapping("/recommendations")
    public List<RecommendationResponse> recommendations(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                                        @RequestParam(defaultValue = "false") boolean includeHistory) {
        return results.recommendations(identities.resolve(principal), includeHistory).stream()
                .map(recommendation -> RecommendationResponse.from(recommendation, json)).toList();
    }

    record EventResponse(UUID id, UUID documentId, String type, String description, OffsetDateTime eventDate,
                         BigDecimal amount, String currency, String status, OffsetDateTime createdAt) {
        static EventResponse from(Event event) {
            return new EventResponse(event.id(), event.documentId(), event.type().value(), event.description(), event.eventDate(),
                    event.amount(), event.currency(), event.status().value(), event.createdAt());
        }
    }

    record RecommendationResponse(UUID id, UUID documentId, String type, Map<String, Object> data,
                                  OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        static RecommendationResponse from(Recommendation recommendation, ObjectMapper json) {
            try {
                JsonNode data = json.readTree(recommendation.data().value());
                Map<String, Object> facts = Map.of(
                        "reason", Map.of("type", requiredText(data, "reason", "type")),
                        "evidence", Map.of(
                                "event_type", requiredText(data, "evidence", "event_type"),
                                "event_date", requiredText(data, "evidence", "event_date")),
                        "impact", Map.of("type", requiredText(data, "impact", "type")),
                        "action", Map.of("type", requiredText(data, "action", "type")));
                return new RecommendationResponse(recommendation.id(), recommendation.documentId(), recommendation.type().value(),
                        facts,
                        recommendation.createdAt(), recommendation.expiresAt());
            } catch (com.fasterxml.jackson.core.JsonProcessingException | ClassCastException failure) {
                throw new IllegalStateException("Persisted recommendation data is invalid", failure);
            }
        }

        private static String requiredText(JsonNode root, String object, String field) {
            JsonNode value = root.path(object).path(field);
            if (!value.isTextual() || value.textValue().isBlank()) {
                throw new IllegalStateException("Persisted recommendation data is invalid");
            }
            return value.textValue();
        }
    }
}
