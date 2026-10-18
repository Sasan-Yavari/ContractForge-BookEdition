package io.contractforge.backend.application.accounting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import io.contractforge.backend.domain.model.UsageCostStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministic, local pricing catalog for the paid providers currently in use. */
public final class VersionedProviderCostCalculator implements ProviderCostCalculator {
    public static final String AWS = "aws";
    public static final String TEXTRACT_SERVICE = "textract-detect-document-text";
    private static final String BEDROCK_SERVICE_PREFIX = "eu.amazon.nova-2-lite-v1:0";
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

    private final ObjectMapper mapper;
    private final String defaultCurrency;
    private final int scale;
    private final RoundingMode roundingMode;
    private final List<TextractPrice> textractPrices;
    private final List<BedrockPrice> bedrockPrices;

    public VersionedProviderCostCalculator(ObjectMapper mapper, String defaultCurrency, int scale,
                                           RoundingMode roundingMode, List<TextractPrice> textractPrices,
                                           List<BedrockPrice> bedrockPrices) {
        this.mapper = Objects.requireNonNull(mapper);
        this.defaultCurrency = requireCurrency(defaultCurrency);
        if (scale < 0 || roundingMode == null) throw new IllegalArgumentException("Invalid rounding configuration");
        this.scale = scale;
        this.roundingMode = roundingMode;
        this.textractPrices = List.copyOf(textractPrices == null ? List.of() : textractPrices);
        this.bedrockPrices = List.copyOf(bedrockPrices == null ? List.of() : bedrockPrices);
    }

    @Override
    public CostCalculation calculate(String provider, String service, String pricingDimension,
                                     JsonValue usageData, OffsetDateTime occurredAt) {
        if (!AWS.equals(provider) || usageData == null || occurredAt == null) return CostCalculation.unknown(defaultCurrency);
        try {
            JsonNode usage = mapper.readTree(usageData.value());
            if (TEXTRACT_SERVICE.equals(service)) return textract(usage, pricingDimension, occurredAt);
            if (service != null && service.startsWith(BEDROCK_SERVICE_PREFIX)) {
                return bedrock(usage, service, pricingDimension, occurredAt);
            }
        } catch (Exception ignored) {
            // Malformed or incomplete provider usage is explicitly unpriced, never guessed.
        }
        return CostCalculation.unknown(defaultCurrency);
    }

    private CostCalculation textract(JsonNode usage, String region, OffsetDateTime occurredAt) {
        JsonNode pages = usage == null ? null : usage.get("pages");
        TextractPrice price = select(textractPrices, region, occurredAt);
        if (price == null || pages == null || !pages.isIntegralNumber() || pages.decimalValue().signum() < 0) {
            return CostCalculation.unknown(price == null ? defaultCurrency : price.currency());
        }
        return calculated(price.pricePerPage().multiply(pages.decimalValue()), price.currency(), price.version());
    }

    private CostCalculation bedrock(JsonNode usage, String model, String inferenceProfile,
                                    OffsetDateTime occurredAt) {
        JsonNode input = usage == null ? null : usage.get("input_tokens");
        JsonNode output = usage == null ? null : usage.get("output_tokens");
        BedrockPrice price = selectBedrock(model, inferenceProfile, occurredAt);
        if (price == null || !validTokenCount(input) || !validTokenCount(output)) {
            return CostCalculation.unknown(price == null ? defaultCurrency : price.currency());
        }
        BigDecimal amount = input.decimalValue().multiply(price.inputPerMillionTokens())
                .add(output.decimalValue().multiply(price.outputPerMillionTokens())).divide(MILLION);
        return calculated(amount, price.currency(), price.version());
    }

    private CostCalculation calculated(BigDecimal amount, String currency, String version) {
        return new CostCalculation(amount.setScale(scale, roundingMode), requireCurrency(currency),
                UsageCostStatus.CALCULATED, version);
    }

    private <T extends EffectivePrice> T select(List<T> prices, String dimension, OffsetDateTime occurredAt) {
        return prices.stream().filter(price -> price.dimension().equals(dimension)
                        && !price.effectiveFrom().isAfter(occurredAt))
                .max(Comparator.comparing(EffectivePrice::effectiveFrom)).orElse(null);
    }

    private BedrockPrice selectBedrock(String model, String inferenceProfile, OffsetDateTime occurredAt) {
        return bedrockPrices.stream().filter(price -> price.model().equals(model)
                        && price.inferenceProfile().equals(inferenceProfile)
                        && !price.effectiveFrom().isAfter(occurredAt))
                .max(Comparator.comparing(EffectivePrice::effectiveFrom)).orElse(null);
    }

    private static boolean validTokenCount(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.decimalValue().signum() >= 0;
    }

    private static String requireCurrency(String value) {
        if (value == null || !value.matches("[A-Z]{3}")) throw new IllegalArgumentException("Currency must be ISO-4217 uppercase");
        return value;
    }

    public interface EffectivePrice {
        String dimension();
        OffsetDateTime effectiveFrom();
    }

    public record TextractPrice(String version, OffsetDateTime effectiveFrom, String region,
                                BigDecimal pricePerPage, String currency) implements EffectivePrice {
        public TextractPrice {
            requireText(version, "version"); requireText(region, "region"); requireText(currency, "currency");
            if (effectiveFrom == null || pricePerPage == null || pricePerPage.signum() < 0) throw new IllegalArgumentException("Invalid Textract price");
        }
        @Override public String dimension() { return region; }
    }

    public record BedrockPrice(String version, OffsetDateTime effectiveFrom, String model,
                               String inferenceProfile, BigDecimal inputPerMillionTokens,
                               BigDecimal outputPerMillionTokens, String currency) implements EffectivePrice {
        public BedrockPrice {
            requireText(version, "version"); requireText(model, "model"); requireText(inferenceProfile, "inferenceProfile"); requireText(currency, "currency");
            if (effectiveFrom == null || inputPerMillionTokens == null || outputPerMillionTokens == null
                    || inputPerMillionTokens.signum() < 0 || outputPerMillionTokens.signum() < 0) throw new IllegalArgumentException("Invalid Bedrock price");
        }
        @Override public String dimension() { return inferenceProfile; }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
    }
}
