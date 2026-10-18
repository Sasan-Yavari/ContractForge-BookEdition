package io.contractforge.backend.api;

import io.contractforge.backend.api.MonitoringResultController.EventResponse;
import io.contractforge.backend.api.MonitoringResultController.RecommendationResponse;
import io.contractforge.backend.application.contract.ContractOverviewService;
import io.contractforge.backend.application.contract.ContractOverviewService.ContractHistoryItem;
import io.contractforge.backend.application.contract.ContractOverviewService.ContractOverview;
import io.contractforge.backend.application.identity.IdentityResolutionService;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import io.contractforge.backend.domain.model.PersistenceModels.Event;
import io.contractforge.backend.domain.model.PersistenceModels.Recommendation;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reads current and historical information for one confirmed owner contract. */
@RestController
@RequestMapping("/v1/contracts/{documentId}/overview")
@Profile("!test")
public final class ContractOverviewController {
    private final ContractOverviewService overviews;
    private final IdentityResolutionService identities;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public ContractOverviewController(ContractOverviewService overviews, IdentityResolutionService identities,
                                      com.fasterxml.jackson.databind.ObjectMapper json) {
        this.overviews = overviews;
        this.identities = identities;
        this.json = json;
    }

    @GetMapping
    public ContractOverviewResponse get(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                        @PathVariable UUID documentId) {
        ContractOverview overview = overviews.overview(identities.resolve(principal), documentId);
        return new ContractOverviewResponse(overview.documentId(),
                overview.thingsToKnow().stream().map(this::event).toList(),
                overview.needsAttention().stream().map(this::recommendation).toList(),
                overview.history().stream().map(this::history).toList());
    }

    private EventResponse event(Event event) {
        return EventResponse.from(event);
    }

    private RecommendationResponse recommendation(Recommendation recommendation) {
        return RecommendationResponse.from(recommendation, json);
    }

    private HistoryResponse history(ContractHistoryItem item) {
        return new HistoryResponse(item.id(), item.occurredAt(),
                item.event() == null ? null : event(item.event()),
                item.recommendation() == null ? null : recommendation(item.recommendation()));
    }

    public record ContractOverviewResponse(UUID documentId, List<EventResponse> thingsToKnow,
                                           List<RecommendationResponse> needsAttention,
                                           List<HistoryResponse> history) { }

    public record HistoryResponse(UUID id, OffsetDateTime occurredAt,
                                  EventResponse event, RecommendationResponse recommendation) { }
}
