package io.contractforge.backend.infrastructure.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Offline, fictional fixtures for optional pricing fields in the current extraction schema. */
class SyntheticPricingSchemaFixturesTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void reportsAllBoundedViolationsForEveryPricingFixture() throws Exception {
        JsonNode schema = loadSchema();
        for (Fixture fixture : fixtures()) {
            JsonNode candidate = json.readTree(fixture.candidateJson());
            List<String> violations = SyntheticSchemaViolationCollector.collect(candidate, schema).stream()
                    .map(SyntheticSchemaViolationCollector.Violation::safeSummary).toList();

            assertThat(violations).containsExactlyInAnyOrderElementsOf(fixture.expectedViolations());
            System.out.println("synthetic_pricing_fixture=" + fixture.name()
                    + " schema_violation_count=" + violations.size());
            for (String violation : violations) {
                System.out.println("synthetic_pricing_violation=" + fixture.name() + "|" + violation);
            }
        }
    }

    private List<Fixture> fixtures() {
        return List.of(
                fixture("all_optional_pricing_structures_omitted", "{}"),
                fixture("pricing_rate_optional_members_omitted", """
                        {"pricing":{"rates":[{}]}}
                        """),
                fixture("pricing_rate_string_members_explicit_null", """
                        {"pricing":{"rates":[{"value":0.155,"unit":null,"currency":null,
                          "period":null,"time_period":null,"index":null}]}}
                        """,
                        "type_mismatch|$.electricity_data.pricing.rates[*].unit|string|null",
                        "type_mismatch|$.electricity_data.pricing.rates[*].currency|string|null",
                        "type_mismatch|$.electricity_data.pricing.rates[*].period|string|null",
                        "type_mismatch|$.electricity_data.pricing.rates[*].time_period|string|null",
                        "type_mismatch|$.electricity_data.pricing.rates[*].index|string|null"),
                fixture("pricing_rate_numeric_value_explicit_null", """
                        {"pricing":{"rates":[{"value":null}]}}
                        """,
                        "type_mismatch|$.electricity_data.pricing.rates[*].value|number|null"),
                fixture("pricing_model_and_rates_explicit_null", """
                        {"pricing":{"model":null,"rates":null}}
                        """,
                        "enum_mismatch|$.electricity_data.pricing.model|string|null",
                        "type_mismatch|$.electricity_data.pricing.model|string|null",
                        "type_mismatch|$.electricity_data.pricing.rates|array|null"),
                fixture("fixed_charge_optional_members_omitted", """
                        {"fixed_charges":[{}]}
                        """),
                fixture("fixed_charge_members_explicit_null", """
                        {"fixed_charges":[{"type":null,"amount":null,"currency":null,"period":null}]}
                        """,
                        "enum_mismatch|$.electricity_data.fixed_charges[*].type|string|null",
                        "type_mismatch|$.electricity_data.fixed_charges[*].type|string|null",
                        "type_mismatch|$.electricity_data.fixed_charges[*].amount|number|null",
                        "type_mismatch|$.electricity_data.fixed_charges[*].currency|string|null",
                        "type_mismatch|$.electricity_data.fixed_charges[*].period|string|null"),
                fixture("return_delivery_optional_members_omitted", """
                        {"return_delivery":{}}
                        """),
                fixture("return_delivery_nullable_members_explicit_null", """
                        {"return_delivery":{"compensation":null,"fees":null}}
                        """),
                fixture("discount_optional_members_omitted", """
                        {"discounts":[{}]}
                        """),
                fixture("discount_members_explicit_null", """
                        {"discounts":[{"type":null,"amount":null,"value":null,
                          "currency":null,"period":null}]}
                        """,
                        "enum_mismatch|$.electricity_data.discounts[*].type|string|null",
                        "type_mismatch|$.electricity_data.discounts[*].type|string|null",
                        "type_mismatch|$.electricity_data.discounts[*].amount|number|null",
                        "type_mismatch|$.electricity_data.discounts[*].value|number|null",
                        "type_mismatch|$.electricity_data.discounts[*].currency|string|null",
                        "type_mismatch|$.electricity_data.discounts[*].period|string|null"));
    }

    private Fixture fixture(String name, String electricityData, String... expectedViolations) {
        String candidate = """
                {
                  "schema_version": "1",
                  "parties": [{"role": "CUSTOMER", "name": "Fictional Buyer"}],
                  "electricity_data": %s
                }
                """.formatted(electricityData);
        return new Fixture(name, candidate, List.of(expectedViolations));
    }

    private JsonNode loadSchema() throws Exception {
        try (var resource = getClass().getResourceAsStream(
                "/extraction/electricity-contract-candidate-v1.schema.json")) {
            assertThat(resource).isNotNull();
            return json.readTree(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private record Fixture(String name, String candidateJson, List<String> expectedViolations) { }
}
