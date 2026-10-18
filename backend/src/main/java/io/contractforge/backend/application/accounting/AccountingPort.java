package io.contractforge.backend.application.accounting;

import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import io.contractforge.backend.domain.model.UsageCostStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Common application boundary used by every money-consuming provider integration. */
public interface AccountingPort {
    void publish(UsageEvent event);

    record UsageEvent(UUID usageEventId, UUID userId, UUID documentId, String operation,
                      String provider, String service, JsonValue usageData,
                      BigDecimal costAmount, String currency, OffsetDateTime occurredAt,
                      UsageCostStatus costStatus, String pricingVersion) {
        public UsageEvent(UUID usageEventId, UUID userId, UUID documentId, String operation,
                          String provider, String service, JsonValue usageData,
                          BigDecimal costAmount, String currency, OffsetDateTime occurredAt) {
            this(usageEventId, userId, documentId, operation, provider, service, usageData,
                    costAmount, currency, occurredAt, UsageCostStatus.LEGACY, null);
        }

        public UsageEvent {
            if (usageEventId == null || userId == null || documentId == null || occurredAt == null
                    || blank(operation) || blank(provider) || blank(service) || usageData == null
                    || costAmount == null || costAmount.signum() < 0 || blank(currency) || costStatus == null
                    || (costStatus == UsageCostStatus.CALCULATED && blank(pricingVersion))
                    || (costStatus == UsageCostStatus.UNKNOWN && (costAmount.signum() != 0 || pricingVersion != null))) {
                throw new IllegalArgumentException("Usage event has invalid required fields");
            }
        }

        private static boolean blank(String value) { return value == null || value.isBlank(); }
    }
}
