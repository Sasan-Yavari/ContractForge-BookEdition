package io.contractforge.backend.infrastructure.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalContractExtractionAdapterTests {
    @Test
    void extractsDocumentedLabelsWithoutProviderInvocation() {
        var result = new LocalContractExtractionAdapter(new ObjectMapper()).extract(
                "CUSTOMER: Acme Buyer\nPROVIDER: Example Energy\nPRICING_MODEL: FIXED", () -> {
                    throw new AssertionError("local extraction must not invoke a paid provider");
                });

        assertThat(result.candidate().parties()).extracting("name")
                .containsExactly("Acme Buyer", "Example Energy");
        assertThat(result.candidate().pricingModel()).isEqualTo("FIXED");
        assertThat(result.usageData().value()).contains("\"total_tokens\":0");
    }
}
