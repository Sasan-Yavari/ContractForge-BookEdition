package io.contractforge.backend.infrastructure.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.extraction.ContractExtractionAccountingProperties;
import io.contractforge.backend.application.extraction.ElectricityContractCandidateValidator;
import io.contractforge.backend.application.extraction.TextExtractionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local")
@EnableConfigurationProperties({TextExtractionProperties.class, ContractExtractionAccountingProperties.class})
final class LocalExtractionConfiguration {
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    ElectricityContractCandidateValidator electricityContractCandidateValidator(ObjectMapper objectMapper) {
        return new ElectricityContractCandidateValidator(objectMapper);
    }
}
