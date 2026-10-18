package io.contractforge.backend.infrastructure.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyntheticSchemaViolationCollectorTests {
    private static final String UNEXPECTED_PROPERTY = "UNEXPECTED_PROPERTY_SENTINEL";
    private static final String UNEXPECTED_PROPERTY_TWO = "UNEXPECTED_PROPERTY_TWO_SENTINEL";
    private static final String UNEXPECTED_VALUE = "UNEXPECTED_VALUE_SENTINEL";
    private static final String MODEL_VALUE = "MODEL_OUTPUT_SENTINEL";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void collectsEverySchemaRuleAtFixedSchemaPathsWithoutValuesOrUnexpectedNames() throws Exception {
        JsonNode schema = loadSchema();
        ObjectNode candidate = json.createObjectNode();
        candidate.put("schema_version", "not-the-expected-version");
        candidate.put(UNEXPECTED_PROPERTY, UNEXPECTED_VALUE);

        ArrayNode parties = candidate.putArray("parties");
        ObjectNode party = parties.addObject();
        party.put("role", "UNEXPECTED_ROLE");
        party.put(UNEXPECTED_PROPERTY, UNEXPECTED_VALUE);

        candidate.put("start_date", 17);
        candidate.put("renewal_type", "UNEXPECTED_RENEWAL");

        ObjectNode electricityData = candidate.putObject("electricity_data");
        electricityData.put(UNEXPECTED_PROPERTY, UNEXPECTED_VALUE);
        electricityData.putNull("consumption");

        ObjectNode pricing = electricityData.putObject("pricing");
        pricing.put("model", "UNEXPECTED_PRICING_MODEL");
        ObjectNode rate = pricing.putArray("rates").addObject();
        rate.put("value", MODEL_VALUE);
        rate.putNull("unit");
        rate.put("currency", false);
        rate.put(UNEXPECTED_PROPERTY, UNEXPECTED_VALUE);
        rate.put(UNEXPECTED_PROPERTY_TWO, UNEXPECTED_VALUE);

        List<String> summaries = SyntheticSchemaViolationCollector.collect(candidate, schema).stream()
                .map(SyntheticSchemaViolationCollector.Violation::safeSummary).toList();

        assertThat(summaries).containsExactlyInAnyOrder(
                "const_mismatch|$.schema_version|string|string",
                "additional_property|$|object|object",
                "enum_mismatch|$.parties[*].role|string|string",
                "required_missing|$.parties[*].name|string|missing",
                "additional_property|$.parties[*]|object|object",
                "type_mismatch|$.start_date|string_or_null|number",
                "enum_mismatch|$.renewal_type|string_or_null|string",
                "additional_property|$.electricity_data|object|object",
                "type_mismatch|$.electricity_data.consumption|object|null",
                "enum_mismatch|$.electricity_data.pricing.model|string|string",
                "type_mismatch|$.electricity_data.pricing.rates[*].value|number|string",
                "type_mismatch|$.electricity_data.pricing.rates[*].unit|string|null",
                "type_mismatch|$.electricity_data.pricing.rates[*].currency|string|boolean",
                "additional_property|$.electricity_data.pricing.rates[*]|object|object",
                "additional_property|$.electricity_data.pricing.rates[*]|object|object");
        assertThat(String.join("\n", summaries))
                .doesNotContain(UNEXPECTED_PROPERTY, UNEXPECTED_PROPERTY_TWO, UNEXPECTED_VALUE, MODEL_VALUE,
                        "UNEXPECTED_ROLE", "UNEXPECTED_RENEWAL", "UNEXPECTED_PRICING_MODEL");
    }

    @Test
    void acceptsAValidCandidateWithoutReportingViolations() throws Exception {
        JsonNode schema = loadSchema();
        JsonNode candidate = json.readTree("""
                {
                  "schema_version": "1",
                  "parties": [{"role": "CUSTOMER", "name": "Synthetic Buyer"}],
                  "electricity_data": {
                    "consumption": {"annual_kwh": null},
                    "pricing": {
                      "model": "FIXED",
                      "rates": [{"value": 0.15, "unit": "kWh", "currency": "EUR", "period": "month"}]
                    }
                  }
                }
                """);

        BedrockContractExtractionAdapter.normalizeOptionalAnnualKwhNull(candidate);

        assertThat(SyntheticSchemaViolationCollector.collect(candidate, schema)).isEmpty();
    }

    private JsonNode loadSchema() throws Exception {
        try (var resource = getClass().getResourceAsStream(
                "/extraction/electricity-contract-candidate-v1.schema.json")) {
            return json.readTree(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
