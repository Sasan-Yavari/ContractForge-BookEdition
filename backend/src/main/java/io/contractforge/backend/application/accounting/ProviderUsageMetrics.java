package io.contractforge.backend.application.accounting;

import io.contractforge.backend.domain.model.UsageCostStatus;
import java.math.BigDecimal;

/** Privacy-safe operational projection of a usage record; deliberately excludes customer/document data. */
public interface ProviderUsageMetrics {
    void record(Usage usage);

    record Usage(String provider, String operation, String service, UsageCostStatus costStatus,
                 BigDecimal costAmount, String currency) {
    }
}
