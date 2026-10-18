package io.contractforge.backend.application.accounting;

import io.contractforge.backend.domain.model.UsageCostStatus;
import java.math.BigDecimal;

/** The auditable result of applying a versioned price to observed provider usage. */
public record CostCalculation(BigDecimal amount, String currency, UsageCostStatus status, String pricingVersion) {
    public CostCalculation {
        if (amount == null || amount.signum() < 0 || currency == null || currency.isBlank()
                || status == null) {
            throw new IllegalArgumentException("Invalid cost calculation");
        }
        if (status == UsageCostStatus.CALCULATED && (pricingVersion == null || pricingVersion.isBlank())) {
            throw new IllegalArgumentException("Calculated cost requires a pricing version");
        }
        if (status == UsageCostStatus.UNKNOWN && (amount.signum() != 0 || pricingVersion != null)) {
            throw new IllegalArgumentException("Unknown cost cannot carry a monetary amount or pricing version");
        }
    }

    public static CostCalculation unknown(String currency) {
        return new CostCalculation(BigDecimal.ZERO, currency, UsageCostStatus.UNKNOWN, null);
    }
}
