package io.contractforge.backend.application.extraction;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Non-sensitive accounting descriptor for the configured model operation. */
@ConfigurationProperties(prefix = "contractforge.extraction.bedrock")
public record ContractExtractionAccountingProperties(String service, String currency) {
    public ContractExtractionAccountingProperties {
        if (service == null || service.isBlank()) service = "eu.amazon.nova-2-lite-v1:0";
        if (currency == null || currency.isBlank()) currency = "EUR";
    }
}
