package io.contractforge.backend.application.monitoring;

import io.contractforge.backend.domain.model.PersistenceModels.Event;
import io.contractforge.backend.domain.model.MonitoringEventType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Produces a factual expiry occurrence only for a trusted fixed-term contract. */
@Service
@Profile("!test")
public class ContractExpiryMonitoringService {

    public Optional<Event> evaluate(ContractMonitoringService.MonitoringInput input) {
        if (input.terms() == null || input.terms().renewalType() != io.contractforge.backend.domain.model.ContractRenewalType.FIXED_TERM) {
            return Optional.empty();
        }
        LocalDate endDate = MonitoringTermDates.endDate(input.terms());
        if (endDate == null || input.evaluatedAt().isBefore(endDate.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime())) {
            return Optional.empty();
        }
        return Optional.of(MonitoringEventFactory.create(input, MonitoringEventType.CONTRACT_EXPIRING, endDate,
                "Electricity contract term ends on " + endDate + "."));
    }
}
