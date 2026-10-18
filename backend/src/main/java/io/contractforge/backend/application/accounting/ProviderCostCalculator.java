package io.contractforge.backend.application.accounting;

import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import java.time.OffsetDateTime;

/** Provider-neutral pricing boundary. Provider adapters only report raw usage. */
@FunctionalInterface
public interface ProviderCostCalculator {
    CostCalculation calculate(String provider, String service, String pricingDimension,
                              JsonValue usageData, OffsetDateTime occurredAt);
}
