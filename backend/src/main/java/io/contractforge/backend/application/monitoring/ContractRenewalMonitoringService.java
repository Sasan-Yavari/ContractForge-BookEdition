package io.contractforge.backend.application.monitoring;

import io.contractforge.backend.domain.model.PersistenceModels.Event;
import io.contractforge.backend.domain.model.MonitoringEventType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Produces a factual renewal occurrence only for a trusted auto-renew contract. */
@Service
@Profile("!test")
public class ContractRenewalMonitoringService {

    public Optional<Event> evaluate(ContractMonitoringService.MonitoringInput input) {
        if (input.terms() == null || input.terms().renewalType() != io.contractforge.backend.domain.model.ContractRenewalType.AUTO_RENEW) {
            return Optional.empty();
        }
        LocalDate renewalDate = MonitoringTermDates.endDate(input.terms());
        if (renewalDate == null || input.evaluatedAt().isBefore(renewalDate.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime())) {
            return Optional.empty();
        }
        return Optional.of(MonitoringEventFactory.create(input, MonitoringEventType.CONTRACT_RENEWING, renewalDate,
                "Electricity contract auto-renews on " + renewalDate + "."));
    }
}
