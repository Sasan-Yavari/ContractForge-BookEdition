package io.contractforge.backend.application.monitoring;

import io.contractforge.backend.application.port.RepositoryPorts.EventRepository;
import io.contractforge.backend.application.port.RepositoryPorts.RecommendationRepository;
import io.contractforge.backend.application.notification.NotificationGenerationPort;
import io.contractforge.backend.domain.model.PersistenceModels.ContractAggregate;
import io.contractforge.backend.domain.model.PersistenceModels.MonitoringContract;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates one deterministic monitoring run and idempotent event persistence. */
@Service
@Profile("!test")
public class ContractMonitoringExecutionService {

    private final ContractMonitoringService monitoring;
    private final ContractExpiryMonitoringService expiry;
    private final ContractRenewalMonitoringService renewal;
    private final EventRepository events;
    private final ContractRecommendationService recommendationService;
    private final RecommendationRepository recommendationRepository;
    private final NotificationGenerationPort notificationGeneration;
    private final Clock clock;

    public ContractMonitoringExecutionService(ContractMonitoringService monitoring,
                                              ContractExpiryMonitoringService expiry,
                                              ContractRenewalMonitoringService renewal,
                                              EventRepository events,
                                              ContractRecommendationService recommendationService,
                                              RecommendationRepository recommendationRepository,
                                              NotificationGenerationPort notificationGeneration, Clock clock) {
        this.monitoring = monitoring;
        this.expiry = expiry;
        this.renewal = renewal;
        this.events = events;
        this.recommendationService = recommendationService;
        this.recommendationRepository = recommendationRepository;
        this.notificationGeneration = notificationGeneration;
        this.clock = clock;
    }

    @Transactional
    public MonitoringResult execute() {
        ContractMonitoringService.MonitoringRun run = monitoring.execute();
        int persistedEvents = 0;
        int persistedRecommendations = 0;
        for (ContractMonitoringService.MonitoringInput input : run.contracts()) {
            PersistedCounts persisted = persist(input);
            persistedEvents += persisted.events();
            persistedRecommendations += persisted.recommendations();
        }
        return new MonitoringResult(run.evaluatedAt(), run.contracts().size(), persistedEvents, persistedRecommendations);
    }

    /** Refreshes derived current monitoring only for an owner-corrected confirmed contract. */
    @Transactional
    public void refreshConfirmedContract(java.util.UUID userId, ContractAggregate aggregate) {
        OffsetDateTime evaluatedAt = OffsetDateTime.now(clock);
        events.cancelCurrentContractEvents(aggregate.contract().documentId());
        recommendationRepository.expireCurrentContractRecommendations(aggregate.contract().documentId(), evaluatedAt);
        var input = ContractMonitoringService.MonitoringInput.from(new MonitoringContract(userId, aggregate), evaluatedAt);
        persist(input);
    }

    private PersistedCounts persist(ContractMonitoringService.MonitoringInput input) {
        int persistedEvents = 0;
        int persistedRecommendations = 0;
        for (var event : List.of(expiry.evaluate(input), renewal.evaluate(input))) {
            if (event.isEmpty()) {
                continue;
            }
            persistedEvents += events.saveIfAbsent(event.get());
            persistedRecommendations += recommendationService.from(event.get())
                    .map(recommendation -> {
                        int persisted = recommendationRepository.saveIfAbsent(recommendation);
                        notificationGeneration.generate(recommendation);
                        return persisted;
                    })
                    .orElse(0);
        }
        return new PersistedCounts(persistedEvents, persistedRecommendations);
    }

    private record PersistedCounts(int events, int recommendations) {
    }

    public record MonitoringResult(OffsetDateTime evaluatedAt, int eligibleContracts, int persistedEvents,
                                   int persistedRecommendations) {
    }
}
