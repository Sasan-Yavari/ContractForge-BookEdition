package io.contractforge.backend.application.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class VersionedProviderCostCalculatorTests {
    private static final OffsetDateTime BEFORE_CHANGE = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    private static final OffsetDateTime AFTER_CHANGE = OffsetDateTime.parse("2026-09-16T00:00:00Z");
    private static final String REGION = "eu-west-1";
    private static final String PROFILE = "eu.amazon.nova-2-lite-v1:0";

    @Test
    void calculatesTextractPagesWithSelectedRegionAndVersion() {
        VersionedProviderCostCalculator calculator = calculator(
                List.of(new VersionedProviderCostCalculator.TextractPrice("textract-v1", BEFORE_CHANGE,
                        REGION, new BigDecimal("0.0015"), "USD")), List.of());

        CostCalculation result = calculator.calculate("aws", VersionedProviderCostCalculator.TEXTRACT_SERVICE,
                REGION, new JsonValue("{\"pages\":3}"), AFTER_CHANGE);

        assertThat(result.amount()).isEqualByComparingTo("0.0045");
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.status()).isEqualTo(io.contractforge.backend.domain.model.UsageCostStatus.CALCULATED);
        assertThat(result.pricingVersion()).isEqualTo("textract-v1");
    }

    @Test
    void calculatesNovaInputAndOutputTokensUsingModelAndInferenceProfile() {
        VersionedProviderCostCalculator calculator = calculator(List.of(), List.of(
                new VersionedProviderCostCalculator.BedrockPrice("nova-v1", BEFORE_CHANGE, PROFILE, PROFILE,
                        new BigDecimal("0.3300"), new BigDecimal("2.7500"), "USD")));

        CostCalculation result = calculator.calculate("aws", PROFILE, PROFILE,
                new JsonValue("{\"input_tokens\":1000,\"output_tokens\":1000,\"total_tokens\":2000}"), AFTER_CHANGE);

        assertThat(result.amount()).isEqualByComparingTo("0.0031");
        assertThat(result.amount().scale()).isEqualTo(4);
        assertThat(result.pricingVersion()).isEqualTo("nova-v1");
    }

    @Test
    void latestEffectivePriceIsSelectedAndHistoricalDateKeepsOlderVersion() {
        VersionedProviderCostCalculator calculator = calculator(List.of(
                new VersionedProviderCostCalculator.TextractPrice("v1", BEFORE_CHANGE, REGION,
                        new BigDecimal("0.0015"), "USD"),
                new VersionedProviderCostCalculator.TextractPrice("v2", AFTER_CHANGE, REGION,
                        new BigDecimal("0.0020"), "USD")), List.of());

        CostCalculation historical = calculator.calculate("aws", VersionedProviderCostCalculator.TEXTRACT_SERVICE,
                REGION, new JsonValue("{\"pages\":1}"), BEFORE_CHANGE.plusDays(1));
        CostCalculation current = calculator.calculate("aws", VersionedProviderCostCalculator.TEXTRACT_SERVICE,
                REGION, new JsonValue("{\"pages\":1}"), AFTER_CHANGE.plusDays(1));

        assertThat(historical.amount()).isEqualByComparingTo("0.0015");
        assertThat(historical.pricingVersion()).isEqualTo("v1");
        assertThat(current.amount()).isEqualByComparingTo("0.0020");
        assertThat(current.pricingVersion()).isEqualTo("v2");
    }

    @Test
    void unavailableUsageOrPriceIsExplicitlyUnknown() {
        VersionedProviderCostCalculator calculator = calculator(List.of(), List.of());

        CostCalculation unknownUsage = calculator.calculate("aws", VersionedProviderCostCalculator.TEXTRACT_SERVICE,
                REGION, new JsonValue("{\"pages\":null}"), AFTER_CHANGE);
        CostCalculation unknownPrice = calculator.calculate("aws", VersionedProviderCostCalculator.TEXTRACT_SERVICE,
                "eu-west-2", new JsonValue("{\"pages\":1}"), AFTER_CHANGE);

        assertThat(unknownUsage.status()).isEqualTo(io.contractforge.backend.domain.model.UsageCostStatus.UNKNOWN);
        assertThat(unknownUsage.amount()).isZero();
        assertThat(unknownPrice.status()).isEqualTo(io.contractforge.backend.domain.model.UsageCostStatus.UNKNOWN);
        assertThat(unknownPrice.pricingVersion()).isNull();
    }

    @Test
    void roundsHalfUpToThePersistenceScale() {
        VersionedProviderCostCalculator calculator = new VersionedProviderCostCalculator(new ObjectMapper(), "USD", 4,
                RoundingMode.HALF_UP, List.of(new VersionedProviderCostCalculator.TextractPrice("v1", BEFORE_CHANGE,
                REGION, new BigDecimal("0.00005"), "USD")), List.of());

        CostCalculation result = calculator.calculate("aws", VersionedProviderCostCalculator.TEXTRACT_SERVICE,
                REGION, new JsonValue("{\"pages\":1}"), AFTER_CHANGE);

        assertThat(result.amount()).isEqualByComparingTo("0.0001");
        assertThat(result.amount().scale()).isEqualTo(4);
    }

    private static VersionedProviderCostCalculator calculator(
            List<VersionedProviderCostCalculator.TextractPrice> textract,
            List<VersionedProviderCostCalculator.BedrockPrice> bedrock) {
        return new VersionedProviderCostCalculator(new ObjectMapper(), "USD", 4, RoundingMode.HALF_UP,
                textract, bedrock);
    }
}
