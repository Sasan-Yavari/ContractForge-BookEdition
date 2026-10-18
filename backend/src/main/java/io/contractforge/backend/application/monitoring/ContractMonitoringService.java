package io.contractforge.backend.application.monitoring;

import io.contractforge.backend.application.port.RepositoryPorts.ContractRepository;
import io.contractforge.backend.domain.model.ContractPartyRole;
import io.contractforge.backend.domain.model.ContractPricingModel;
import io.contractforge.backend.domain.model.ContractRenewalType;
import io.contractforge.backend.domain.model.ContractType;
import io.contractforge.backend.domain.model.PersistenceModels.ContractParty;
import io.contractforge.backend.domain.model.PersistenceModels.ContractTerms;
import io.contractforge.backend.domain.model.PersistenceModels.MonitoringContract;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider- and trigger-independent monitoring execution boundary.
 *
 * This boundary supplies trusted, ownership-preserving inputs to dedicated
 * monitoring concerns and contains no generic rule engine.
 */
@Service
@Profile("!test")
public class ContractMonitoringService {

    private final ContractRepository contracts;
    private final Clock clock;

    public ContractMonitoringService(ContractRepository contracts, Clock clock) {
        this.contracts = contracts;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MonitoringRun execute() {
        OffsetDateTime evaluatedAt = OffsetDateTime.now(clock);
        List<MonitoringInput> inputs = contracts.findEligibleForMonitoring().stream()
                .map(contract -> MonitoringInput.from(contract, evaluatedAt))
                .toList();
        return new MonitoringRun(evaluatedAt, inputs);
    }

    public record MonitoringRun(OffsetDateTime evaluatedAt, List<MonitoringInput> contracts) {
        public MonitoringRun {
            if (evaluatedAt == null || contracts == null) {
                throw new IllegalArgumentException("Monitoring run values are required");
            }
            contracts = List.copyOf(contracts);
        }
    }

    /** Stable application input for dedicated expiry, renewal, and other monitors. */
    public record MonitoringInput(UUID userId, UUID documentId, ContractType contractType,
                                  List<Party> parties, Terms terms, OffsetDateTime evaluatedAt) {
        public MonitoringInput(UUID userId, UUID documentId, String contractType,
                               List<Party> parties, Terms terms, OffsetDateTime evaluatedAt) {
            this(userId, documentId, ContractType.fromValue(contractType), parties, terms, evaluatedAt);
        }

        static MonitoringInput from(MonitoringContract contract, OffsetDateTime evaluatedAt) {
            var aggregate = contract.aggregate();
            return new MonitoringInput(contract.userId(), contract.documentId(), aggregate.contract().type(),
                    aggregate.parties().stream().map(Party::from).toList(), Terms.from(aggregate.terms()), evaluatedAt);
        }
    }

    public record Party(ContractPartyRole role, String name) {
        public Party(String role, String name) {
            this(ContractPartyRole.fromValue(role), name);
        }

        static Party from(ContractParty party) {
            return new Party(party.role(), party.name());
        }
    }

    public record Terms(java.time.LocalDate startDate, java.time.LocalDate endDate, Integer contractDuration,
                        ContractRenewalType renewalType, ContractPricingModel pricingModel, String electricityData) {
        public Terms(java.time.LocalDate startDate, java.time.LocalDate endDate, Integer contractDuration,
                     String renewalType, String pricingModel, String electricityData) {
            this(startDate, endDate, contractDuration,
                    renewalType == null ? null : ContractRenewalType.fromValue(renewalType),
                    pricingModel == null ? null : ContractPricingModel.fromValue(pricingModel), electricityData);
        }

        static Terms from(ContractTerms terms) {
            return terms == null ? null : new Terms(terms.startDate(), terms.endDate(), terms.contractDuration(),
                    terms.renewalType(), terms.pricingModel(),
                    terms.electricityData() == null ? null : terms.electricityData().value());
        }
    }
}
