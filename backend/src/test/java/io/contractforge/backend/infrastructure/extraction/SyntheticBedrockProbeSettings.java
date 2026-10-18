package io.contractforge.backend.infrastructure.extraction;

import java.time.Duration;
import java.util.Map;

/** Resolves the same externally configurable Bedrock settings as the production application. */
final class SyntheticBedrockProbeSettings {
    private static final String DEFAULT_REGION = "eu-west-1";
    private static final String DEFAULT_MODEL_ID = "eu.amazon.nova-2-lite-v1:0";
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private SyntheticBedrockProbeSettings() { }

    static BedrockProperties fromEnvironment(Map<String, String> environment) {
        return new BedrockProperties(
                configured(environment, "CONTRACTFORGE_BEDROCK_REGION", DEFAULT_REGION),
                configured(environment, "CONTRACTFORGE_BEDROCK_MODEL_ID", DEFAULT_MODEL_ID),
                Integer.parseInt(configured(environment, "CONTRACTFORGE_BEDROCK_MAX_OUTPUT_TOKENS",
                        Integer.toString(DEFAULT_MAX_OUTPUT_TOKENS))),
                Duration.parse(configured(environment, "CONTRACTFORGE_BEDROCK_TIMEOUT", DEFAULT_TIMEOUT.toString())));
    }

    private static String configured(Map<String, String> environment, String name, String fallback) {
        String configured = environment.get(name);
        return configured == null || configured.isBlank() ? fallback : configured;
    }
}
