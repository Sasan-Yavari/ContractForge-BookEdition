package io.contractforge.backend.application.monitoring;

import io.contractforge.backend.domain.model.ContractType;
import static org.assertj.core.api.Assertions.assertThat;

import io.contractforge.backend.application.port.RepositoryPorts;
import io.contractforge.backend.domain.model.PersistenceModels;
import io.contractforge.backend.domain.model.PersistenceModels.Contract;
import io.contractforge.backend.domain.model.PersistenceModels.ContractAggregate;
import io.contractforge.backend.domain.model.PersistenceModels.ContractParty;
import io.contractforge.backend.domain.model.PersistenceModels.ContractTerms;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import io.contractforge.backend.domain.model.PersistenceModels.MonitoringContract;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractMonitoringServiceTests {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final OffsetDateTime EVALUATED_AT = OffsetDateTime.parse("2026-09-16T12:00:00Z");

    @Test
    void executionUsesInjectedTimeAndMapsTrustedOwnershipAndTerms() {
        MonitoringContract eligible = new MonitoringContract(USER_ID, new ContractAggregate(
                new Contract(DOCUMENT_ID, "ELECTRICITY", "ACTIVE", new JsonValue("{\"approved\":true}"),
                        3, EVALUATED_AT, EVALUATED_AT, null),
                List.of(new ContractParty(UUID.randomUUID(), DOCUMENT_ID, "CUSTOMER", "Ada Lovelace")),
                new ContractTerms(DOCUMENT_ID, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), 12,
                        "FIXED_TERM", "FIXED", new JsonValue("{\"consumption\":{\"annual_kwh\":3200}}"))));
        ContractMonitoringService service = new ContractMonitoringService(new Contracts(List.of(eligible)),
                Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC));

        ContractMonitoringService.MonitoringRun run = service.execute();

        assertThat(run.evaluatedAt()).isEqualTo(EVALUATED_AT);
        assertThat(run.contracts()).singleElement().satisfies(input -> {
            assertThat(input.userId()).isEqualTo(USER_ID);
            assertThat(input.documentId()).isEqualTo(DOCUMENT_ID);
            assertThat(input.contractType()).isEqualTo(ContractType.ELECTRICITY);
            assertThat(input.parties()).extracting(ContractMonitoringService.Party::name)
                    .containsExactly("Ada Lovelace");
            assertThat(input.terms().endDate()).isEqualTo(LocalDate.of(2027, 1, 1));
            assertThat(input.terms().electricityData()).contains("annual_kwh");
            assertThat(input.evaluatedAt()).isEqualTo(EVALUATED_AT);
        });
    }

    private static final class Contracts implements RepositoryPorts.ContractRepository {
        private final List<MonitoringContract> eligible;

        private Contracts(List<MonitoringContract> eligible) {
            this.eligible = eligible;
        }

        @Override
        public Optional<ContractAggregate> findByDocumentId(UUID documentId) {
            return Optional.empty();
        }

        @Override
        public List<MonitoringContract> findEligibleForMonitoring() {
            return eligible;
        }

        @Override
        public void save(ContractAggregate aggregate) { }

        @Override
        public int updateDraft(ContractAggregate aggregate, long expectedVersion) { return 0; }
        public int updateActive(ContractAggregate aggregate, long expectedVersion) { return 0; }

        @Override
        public int approveDraft(UUID documentId, long expectedVersion) { return 0; }
    }
}
