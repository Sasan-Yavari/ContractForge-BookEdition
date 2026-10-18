package io.contractforge.backend.infrastructure.accounting;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.accounting.ProviderCostCalculator;
import io.contractforge.backend.application.accounting.VersionedProviderCostCalculator;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ProviderPricingProperties.class)
public class ProviderPricingConfiguration {
    @Bean
    ProviderCostCalculator providerCostCalculator(ObjectMapper mapper, ProviderPricingProperties properties) {
        return new VersionedProviderCostCalculator(mapper, properties.getDefaultCurrency(), properties.getScale(),
                properties.getRoundingMode(), properties.getTextract().stream()
                        .map(price -> new VersionedProviderCostCalculator.TextractPrice(price.version(), price.effectiveFrom(),
                                price.region(), price.pricePerPage(), price.currency())).collect(Collectors.toList()),
                properties.getBedrock().stream()
                        .map(price -> new VersionedProviderCostCalculator.BedrockPrice(price.version(), price.effectiveFrom(),
                                price.model(), price.inferenceProfile(), price.inputPerMillionTokens(),
                                price.outputPerMillionTokens(), price.currency())).collect(Collectors.toList()));
    }
}
