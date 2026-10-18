package io.contractforge.backend.application.monitoring;

import io.contractforge.backend.domain.model.PersistenceModels.Event;
import io.contractforge.backend.domain.model.EventStatus;
import io.contractforge.backend.domain.model.MonitoringEventType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

final class MonitoringEventFactory {

    private MonitoringEventFactory() {
    }

    static Event create(ContractMonitoringService.MonitoringInput input, MonitoringEventType type,
                        LocalDate occurrenceDate, String description) {
        String sourceReference = "contract-term:" + type.value() + ":" + occurrenceDate;
        UUID eventId = UUID.nameUUIDFromBytes((input.documentId() + ":" + sourceReference)
                .getBytes(StandardCharsets.UTF_8));
        return new Event(eventId, input.userId(), input.documentId(), type, description,
                occurrenceDate.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime(), null, null, EventStatus.ACTIVE,
                sourceReference, input.evaluatedAt());
    }
}
