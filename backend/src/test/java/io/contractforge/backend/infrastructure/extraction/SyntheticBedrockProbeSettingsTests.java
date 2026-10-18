package io.contractforge.backend.infrastructure.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SyntheticBedrockProbeSettingsTests {
    @Test
    void usesTheSameBedrockDefaultsAsTheProductionConfiguration() {
        BedrockProperties settings = SyntheticBedrockProbeSettings.fromEnvironment(Map.of());

        assertEquals("eu-west-1", settings.region());
        assertEquals("eu.amazon.nova-2-lite-v1:0", settings.modelId());
        assertEquals(4096, settings.maxOutputTokens());
        assertEquals(Duration.ofSeconds(30), settings.timeout());
    }

    @Test
    void usesBedrockSpecificRuntimeOverridesAndIgnoresTheUnrelatedAwsRegionVariable() {
        BedrockProperties settings = SyntheticBedrockProbeSettings.fromEnvironment(Map.of(
                "AWS_REGION", "eu-north-1",
                "CONTRACTFORGE_BEDROCK_REGION", "eu-west-3",
                "CONTRACTFORGE_BEDROCK_MODEL_ID", "eu.amazon.nova-2-lite-v1:0",
                "CONTRACTFORGE_BEDROCK_MAX_OUTPUT_TOKENS", "2048",
                "CONTRACTFORGE_BEDROCK_TIMEOUT", "PT45S"));

        assertEquals("eu-west-3", settings.region());
        assertEquals("eu.amazon.nova-2-lite-v1:0", settings.modelId());
        assertEquals(2048, settings.maxOutputTokens());
        assertEquals(Duration.ofSeconds(45), settings.timeout());
    }

    @Test
    void ignoresAwsRegionWhenBedrockSpecificRegionIsNotConfigured() {
        BedrockProperties settings = SyntheticBedrockProbeSettings.fromEnvironment(
                Map.of("AWS_REGION", "eu-north-1"));

        assertEquals("eu-west-1", settings.region());
    }
}
