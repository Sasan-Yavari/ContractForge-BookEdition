package io.contractforge.backend.application.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.port.RepositoryPorts.ContractRepository;
import io.contractforge.backend.application.port.RepositoryPorts.DocumentRepository;
import io.contractforge.backend.application.port.RepositoryPorts.EventRepository;
import io.contractforge.backend.application.port.RepositoryPorts.RecommendationRepository;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import io.contractforge.backend.domain.model.ContractStatus;
import io.contractforge.backend.domain.model.EventStatus;
import io.contractforge.backend.domain.model.MonitoringEventType;
import io.contractforge.backend.domain.model.PersistenceModels.Event;
import io.contractforge.backend.domain.model.PersistenceModels.Recommendation;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Contract-scoped current information and useful history projection for a confirmed contract. */
@Service
@Profile("!test")
public class ContractOverviewService {
    private final DocumentRepository documents;
    private final ContractRepository contracts;
    private final EventRepository events;
    private final RecommendationRepository recommendations;
    private final ObjectMapper json;
    private final Clock clock;

    public ContractOverviewService(DocumentRepository documents, ContractRepository contracts,
                                   EventRepository events, RecommendationRepository recommendations,
                                   ObjectMapper json, Clock clock) {
        this.documents = documents;
        this.contracts = contracts;
        this.events = events;
        this.recommendations = recommendations;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ContractOverview overview(AuthenticatedApplicationUser user, UUID documentId) {
        if (user == null || user.userId() == null || documentId == null
                || documents.findById(documentId).filter(document -> user.userId().equals(document.userId())).isEmpty()
                || contracts.findByDocumentId(documentId).filter(aggregate ->
                        aggregate.contract().status() == ContractStatus.ACTIVE).isEmpty()) {
            throw new ContractOverviewNotFoundException();
        }

        OffsetDateTime evaluatedAt = OffsetDateTime.now(clock);
        List<Event> contractEvents = events.findByUserIdAndDocumentId(user.userId(), documentId);
        List<Recommendation> contractRecommendations = recommendations.findByUserIdAndDocumentId(
                user.userId(), documentId, true, evaluatedAt);

        List<Recommendation> needsAttention = contractRecommendations.stream()
                .filter(recommendation -> isCurrent(recommendation, evaluatedAt)).toList();
        var currentRecommendationEvents = new java.util.HashSet<EventReference>();
        needsAttention.stream().map(this::sourceEvent).flatMap(Optional::stream)
                .forEach(currentRecommendationEvents::add);

        List<Event> thingsToKnow = contractEvents.stream()
                .filter(event -> event.status() == EventStatus.ACTIVE)
                .filter(event -> !currentRecommendationEvents.contains(reference(event)))
                .toList();

        List<ContractHistoryItem> history = history(contractEvents, contractRecommendations, evaluatedAt);
        return new ContractOverview(documentId, thingsToKnow, needsAttention, history);
    }

    private List<ContractHistoryItem> history(List<Event> contractEvents,
                                              List<Recommendation> contractRecommendations,
                                              OffsetDateTime evaluatedAt) {
        Map<EventReference, HistoryBuilder> byEvent = new HashMap<>();
        List<HistoryBuilder> items = new ArrayList<>();
        for (Event event : contractEvents) {
            if (event.status() == EventStatus.ACTIVE) continue;
            HistoryBuilder item = new HistoryBuilder(event.eventDate(), event.id());
            item.event = event;
            EventReference reference = reference(event);
            if (reference == null) {
                items.add(item);
            } else {
                byEvent.put(reference, item);
                items.add(item);
            }
        }
        for (Recommendation recommendation : contractRecommendations) {
            if (isCurrent(recommendation, evaluatedAt)) continue;
            EventReference reference = sourceEvent(recommendation).orElse(null);
            HistoryBuilder item = reference == null ? null : byEvent.get(reference);
            if (item == null) {
                item = new HistoryBuilder(recommendation.createdAt(), recommendation.id());
                items.add(item);
            }
            item.recommendation = recommendation;
        }
        return items.stream()
                .map(HistoryBuilder::toItem)
                .sorted(Comparator.comparing(ContractHistoryItem::occurredAt, Comparator.reverseOrder())
                        .thenComparing(item -> item.id().toString(), Comparator.reverseOrder()))
                .toList();
    }

    private Optional<EventReference> sourceEvent(Recommendation recommendation) {
        try {
            JsonNode evidence = json.readTree(recommendation.data().value()).path("evidence");
            JsonNode type = evidence.path("event_type");
            JsonNode source = evidence.path("source_reference");
            if (!type.isTextual() || type.textValue().isBlank() || !source.isTextual() || source.textValue().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new EventReference(MonitoringEventType.fromValue(type.textValue()), source.textValue()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalStateException("Persisted recommendation data is invalid", failure);
        }
    }

    private static EventReference reference(Event event) {
        return event.sourceReference() == null ? null : new EventReference(event.type(), event.sourceReference());
    }

    private static boolean isCurrent(Recommendation recommendation, OffsetDateTime evaluatedAt) {
        return recommendation.expiresAt() == null || recommendation.expiresAt().isAfter(evaluatedAt);
    }

    public record ContractOverview(UUID documentId, List<Event> thingsToKnow,
                                   List<Recommendation> needsAttention, List<ContractHistoryItem> history) {
        public ContractOverview {
            thingsToKnow = List.copyOf(thingsToKnow);
            needsAttention = List.copyOf(needsAttention);
            history = List.copyOf(history);
        }
    }

    public record ContractHistoryItem(UUID id, OffsetDateTime occurredAt,
                                      Event event, Recommendation recommendation) { }

    private record EventReference(MonitoringEventType type, String sourceReference) { }

    private static final class HistoryBuilder {
        private final OffsetDateTime occurredAt;
        private final UUID eventId;
        private Event event;
        private Recommendation recommendation;

        private HistoryBuilder(OffsetDateTime occurredAt, UUID eventId) {
            this.occurredAt = occurredAt;
            this.eventId = eventId;
        }

        private ContractHistoryItem toItem() {
            return new ContractHistoryItem(eventId != null ? eventId : recommendation.id(), occurredAt,
                    event, recommendation);
        }
    }

    public static final class ContractOverviewNotFoundException extends RuntimeException { }
}
