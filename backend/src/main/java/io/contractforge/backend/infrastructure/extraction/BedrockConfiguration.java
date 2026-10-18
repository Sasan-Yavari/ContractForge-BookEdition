package io.contractforge.backend.infrastructure.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.extraction.ContractExtractionPort;
import io.contractforge.backend.application.extraction.ContractExtractionAccountingProperties;
import io.contractforge.backend.application.extraction.ElectricityContractCandidateValidator;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

@Configuration
@Profile("!local")
@EnableConfigurationProperties({BedrockProperties.class, ContractExtractionAccountingProperties.class})
public class BedrockConfiguration {
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    BedrockRuntimeClient bedrockRuntimeClient(BedrockProperties properties) {
        return BedrockRuntimeClient.builder().region(Region.of(properties.region()))
                .overrideConfiguration(c -> c.apiCallTimeout(properties.timeout())
                        .apiCallAttemptTimeout(properties.timeout())).build();
    }

    @Bean
    ElectricityContractCandidateValidator electricityContractCandidateValidator(ObjectMapper objectMapper) {
        return new ElectricityContractCandidateValidator(objectMapper);
    }
}

@ConfigurationProperties(prefix = "contractforge.extraction.bedrock")
record BedrockProperties(String region, String modelId, int maxOutputTokens, Duration timeout) {
    private static final int MAX_OUTPUT_TOKENS = 8192;

    BedrockProperties {
        if (region == null || region.isBlank()) region = "eu-west-1";
        if (modelId == null || modelId.isBlank()) modelId = "eu.amazon.nova-2-lite-v1:0";
        if (maxOutputTokens <= 0) maxOutputTokens = 4096;
        if (maxOutputTokens > MAX_OUTPUT_TOKENS) throw new IllegalArgumentException("Bedrock output token limit is too high");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) timeout = Duration.ofSeconds(30);
        if (!region.startsWith("eu-") || !modelId.startsWith("eu.")) {
            throw new IllegalArgumentException("Bedrock must use an EU inference profile");
        }
    }
}
