package io.contractforge.backend.infrastructure.accounting;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contractforge.accounting.pricing")
public class ProviderPricingProperties {
    private String defaultCurrency = "USD";
    private int scale = 4;
    private java.math.RoundingMode roundingMode = java.math.RoundingMode.HALF_UP;
    private List<TextractPrice> textract = new ArrayList<>();
    private List<BedrockPrice> bedrock = new ArrayList<>();

    public String getDefaultCurrency() { return defaultCurrency; }
    public void setDefaultCurrency(String value) { defaultCurrency = value; }
    public int getScale() { return scale; }
    public void setScale(int value) { scale = value; }
    public java.math.RoundingMode getRoundingMode() { return roundingMode; }
    public void setRoundingMode(java.math.RoundingMode value) { roundingMode = value; }
    public List<TextractPrice> getTextract() { return textract; }
    public void setTextract(List<TextractPrice> value) { textract = value; }
    public List<BedrockPrice> getBedrock() { return bedrock; }
    public void setBedrock(List<BedrockPrice> value) { bedrock = value; }

    public record TextractPrice(String version, OffsetDateTime effectiveFrom, String region,
                                BigDecimal pricePerPage, String currency) { }
    public record BedrockPrice(String version, OffsetDateTime effectiveFrom, String model,
                               String inferenceProfile, BigDecimal inputPerMillionTokens,
                               BigDecimal outputPerMillionTokens, String currency) { }
}
