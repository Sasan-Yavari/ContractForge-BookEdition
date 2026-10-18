package io.contractforge.backend.application.monitoring;

import io.contractforge.backend.domain.model.EventStatus;
import io.contractforge.backend.domain.model.MonitoringEventType;
import static org.assertj.core.api.Assertions.assertThat;

import io.contractforge.backend.application.port.RepositoryPorts;
import io.contractforge.backend.domain.model.PersistenceModels.ContractAggregate;
import io.contractforge.backend.domain.model.PersistenceModels.Event;
import io.contractforge.backend.domain.model.PersistenceModels.Recommendation;
import io.contractforge.backend.domain.model.PersistenceModels.MonitoringContract;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractEventMonitoringTests {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final OffsetDateTime EVALUATED_AT = OffsetDateTime.parse("2027-01-01T00:00:00Z");

    @Test
    void fixedTermProducesExpiryEventOnOrAfterTrustedEndDate() {
        ContractExpiryMonitoringService service = new ContractExpiryMonitoringService();

        Optional<Event> event = service.evaluate(input("FIXED_TERM", LocalDate.of(2026, 12, 31), null));

        assertThat(event).get().satisfies(value -> {
            assertThat(value.type()).isEqualTo(MonitoringEventType.CONTRACT_EXPIRING);
            assertThat(value.eventDate()).isEqualTo(OffsetDateTime.parse("2026-12-31T00:00:00Z"));
            assertThat(value.status()).isEqualTo(EventStatus.ACTIVE);
            assertThat(value.userId()).isEqualTo(USER_ID);
            assertThat(value.documentId()).isEqualTo(DOCUMENT_ID);
            assertThat(value.amount()).isNull();
            assertThat(value.currency()).isNull();
            assertThat(value.description()).isEqualTo("Electricity contract term ends on 2026-12-31.");
        });
    }

    @Test
    void autoRenewProducesEventOnDerivedRenewalDate() {
        ContractRenewalMonitoringService service = new ContractRenewalMonitoringService();
        ContractMonitoringService.MonitoringInput input = input("AUTO_RENEW", null, 12);

        assertThat(service.evaluate(input)).get().satisfies(event -> {
            assertThat(event.type()).isEqualTo(MonitoringEventType.CONTRACT_RENEWING);
            assertThat(event.eventDate()).isEqualTo(OffsetDateTime.parse("2027-01-01T00:00:00Z"));
        });
    }

    @Test
    void fixedTermProducesNoEventBeforeTheApplicableDate() {
        assertThat(new ContractExpiryMonitoringService().evaluate(
                input("FIXED_TERM", LocalDate.of(2027, 1, 2), null))).isEmpty();
    }

    @Test
    void insufficientDatesAndOpenEndedTermsProduceNoInventedEvent() {
        ContractExpiryMonitoringService expiry = new ContractExpiryMonitoringService();
        ContractRenewalMonitoringService renewal = new ContractRenewalMonitoringService();

        assertThat(expiry.evaluate(input("FIXED_TERM", null, null))).isEmpty();
        assertThat(expiry.evaluate(input("OPEN_ENDED", LocalDate.of(2026, 12, 31), null))).isEmpty();
        assertThat(renewal.evaluate(input("AUTO_RENEW", null, null))).isEmpty();
    }

    @Test
    void repeatedExecutionPersistsOneOccurrence() {
        MonitoringContract eligible = new MonitoringContract(USER_ID, new ContractAggregate(
                new io.contractforge.backend.domain.model.PersistenceModels.Contract(DOCUMENT_ID, "ELECTRICITY", "ACTIVE",
                        new io.contractforge.backend.domain.model.PersistenceModels.JsonValue("{}"), 0,
                        EVALUATED_AT, EVALUATED_AT, null), List.of(),
                new io.contractforge.backend.domain.model.PersistenceModels.ContractTerms(DOCUMENT_ID,
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 12,
                        "FIXED_TERM", "FIXED", null)));
        ContractMonitoringService monitoring = new ContractMonitoringService(new Contracts(List.of(eligible)),
                Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC));
        EventStore events = new EventStore();
        RecommendationStore recommendations = new RecommendationStore();
        ContractMonitoringExecutionService execution = new ContractMonitoringExecutionService(monitoring,
                new ContractExpiryMonitoringService(), new ContractRenewalMonitoringService(), events,
                new ContractRecommendationService(new com.fasterxml.jackson.databind.ObjectMapper()), recommendations,
                recommendation -> { }, Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC));

        var first = execution.execute();
        var second = execution.execute();

        assertThat(first.persistedEvents()).isEqualTo(1);
        assertThat(first.persistedRecommendations()).isEqualTo(1);
        assertThat(second.persistedEvents()).isZero();
        assertThat(second.persistedRecommendations()).isZero();
        assertThat(events.rows).hasSize(1);
        assertThat(recommendations.rows).hasSize(1);
    }

    private static ContractMonitoringService.MonitoringInput input(String renewalType, LocalDate endDate,
                                                                    Integer duration) {
        return new ContractMonitoringService.MonitoringInput(USER_ID, DOCUMENT_ID, "ELECTRICITY", List.of(),
                new ContractMonitoringService.Terms(LocalDate.of(2026, 1, 1), endDate, duration,
                        renewalType, "FIXED", null), EVALUATED_AT);
    }

    private static final class Contracts implements RepositoryPorts.ContractRepository {
        private final List<MonitoringContract> rows;

        private Contracts(List<MonitoringContract> rows) {
            this.rows = rows;
        }

        @Override public Optional<ContractAggregate> findByDocumentId(UUID documentId) { return Optional.empty(); }
        @Override public List<MonitoringContract> findEligibleForMonitoring() { return rows; }
        @Override public void save(ContractAggregate aggregate) { }
        @Override public int updateDraft(ContractAggregate aggregate, long expectedVersion) { return 0; }
        @Override public int updateActive(ContractAggregate aggregate, long expectedVersion) { return 0; }
        @Override public int approveDraft(UUID documentId, long expectedVersion) { return 0; }
    }

    private static final class EventStore implements RepositoryPorts.EventRepository {
        private final List<Event> rows = new java.util.ArrayList<>();
        private final Set<String> occurrences = new HashSet<>();

        @Override public int cancelCurrentContractEvents(UUID documentId) { return 0; }

        @Override public void save(Event event) { rows.add(event); }
        @Override public int saveIfAbsent(Event event) {
            return occurrences.add(event.documentId() + ":" + event.type() + ":" + event.sourceReference())
                    ? add(event) : 0;
        }
        private int add(Event event) { rows.add(event); return 1; }
        @Override public List<Event> findByUserId(UUID userId) {
            return rows.stream().filter(event -> event.userId().equals(userId)).toList();
        }
        @Override public List<Event> findByUserIdAndDocumentId(UUID userId, UUID documentId) {
            return rows.stream().filter(event -> event.userId().equals(userId))
                    .filter(event -> event.documentId().equals(documentId)).toList();
        }
        @Override public Optional<Event> findById(UUID id) { return rows.stream().filter(event -> event.id().equals(id)).findFirst(); }
    }

    private static final class RecommendationStore implements RepositoryPorts.RecommendationRepository {
        private final List<io.contractforge.backend.domain.model.PersistenceModels.Recommendation> rows = new java.util.ArrayList<>();

        @Override public int expireCurrentContractRecommendations(UUID documentId, OffsetDateTime expiredAt) { return 0; }

        @Override public void save(io.contractforge.backend.domain.model.PersistenceModels.Recommendation recommendation) {
            rows.add(recommendation);
        }

        @Override public int saveIfAbsent(io.contractforge.backend.domain.model.PersistenceModels.Recommendation recommendation) {
            if (rows.stream().anyMatch(existing -> existing.id().equals(recommendation.id()))) {
                return 0;
            }
            rows.add(recommendation);
            return 1;
        }

        @Override public List<io.contractforge.backend.domain.model.PersistenceModels.Recommendation> findByUserId(
                UUID userId, boolean includeHistory, OffsetDateTime evaluatedAt) {
            return rows.stream().filter(recommendation -> recommendation.userId().equals(userId))
                    .filter(recommendation -> includeHistory || recommendation.expiresAt() == null
                            || recommendation.expiresAt().isAfter(evaluatedAt)).toList();
        }

        @Override public List<Recommendation>
        findByUserIdAndDocumentId(UUID userId, UUID documentId, boolean includeHistory, OffsetDateTime evaluatedAt) {
            return rows.stream().filter(recommendation -> recommendation.userId().equals(userId))
                    .filter(recommendation -> recommendation.documentId().equals(documentId))
                    .filter(recommendation -> includeHistory || recommendation.expiresAt() == null
                            || recommendation.expiresAt().isAfter(evaluatedAt)).toList();
        }

        @Override public Optional<Recommendation> findById(UUID id) {
            return rows.stream().filter(recommendation -> recommendation.id().equals(id)).findFirst();
        }
    }
}
