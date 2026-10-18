package io.contractforge.backend.infrastructure.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.extraction.ContractExtractionPort;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Deterministic sample-format extraction; used because Bedrock is not emulated by LocalStack. */
@Component
@Profile("local")
final class LocalContractExtractionAdapter implements ContractExtractionPort {
    private final ObjectMapper json;

    LocalContractExtractionAdapter(ObjectMapper json) { this.json = json; }

    @Override
    public ExtractionResult extract(String text, InvocationRecorder invocationRecorder) {
        if (text == null || text.isBlank()) {
            throw new PermanentExtractionException(new IllegalArgumentException("No local document text"));
        }
        String customer = value(text, "CUSTOMER:", "Local customer");
        String provider = value(text, "PROVIDER:", "Local provider");
        String pricing = value(text, "PRICING_MODEL:", "FIXED");
        if (!List.of("FIXED", "VARIABLE", "DYNAMIC").contains(pricing)) pricing = "FIXED";
        Candidate candidate = new Candidate(CURRENT_SCHEMA_VERSION,
                List.of(new Party("CUSTOMER", customer), new Party("PROVIDER", provider)),
                null, null, null, null, pricing, serialize(new PricingData(new Pricing(pricing))));
        return new ExtractionResult(candidate, serialize(new ProviderUsage(0, 0, 0)));
    }

    private JsonValue serialize(Object value) {
        try {
            return new JsonValue(json.writeValueAsString(value));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Local extraction data could not be serialized", failure);
        }
    }

    private record PricingData(Pricing pricing) { }
    private record Pricing(String model) { }
    private record ProviderUsage(Integer input_tokens, Integer output_tokens, Integer total_tokens) { }

    private String value(String text, String label, String fallback) {
        return text.lines().map(String::trim)
                .filter(line -> line.regionMatches(true, 0, label, 0, label.length()))
                .map(line -> line.substring(label.length()).trim())
                .filter(value -> !value.isBlank()).findFirst().orElse(fallback);
    }
}
