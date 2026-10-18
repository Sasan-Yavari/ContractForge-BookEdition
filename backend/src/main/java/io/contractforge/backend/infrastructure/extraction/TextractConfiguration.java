package io.contractforge.backend.infrastructure.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import io.contractforge.backend.application.extraction.TextExtractionProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.textract.TextractClient;

@Configuration
@Profile("!local")
@EnableConfigurationProperties({TextractProperties.class, TextExtractionProperties.class})
public class TextractConfiguration {
    @Bean
    TextractClient textractClient(TextractProperties properties) {
        return TextractClient.builder().region(Region.of(properties.region())).build();
    }
}

@ConfigurationProperties(prefix = "contractforge.extraction.textract")
record TextractProperties(String region) {
    TextractProperties {
        if (region == null || region.isBlank()) region = "eu-west-1";
    }
}
