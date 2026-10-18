package io.contractforge.backend.infrastructure.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BedrockConfigurationTests {
    @Test
    void defaultsToConfiguredNovaTwoLiteAndEuropeanInference() {
        BedrockProperties properties = new BedrockProperties(null, null, 0, null);

        assertThat(properties.region()).isEqualTo("eu-west-1");
        assertThat(properties.modelId()).isEqualTo("eu.amazon.nova-2-lite-v1:0");
        assertThat(properties.maxOutputTokens()).isEqualTo(4096);
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void rejectsNonEuropeanInferenceConfiguration() {
        assertThatThrownBy(() -> new BedrockProperties("us-east-1", "model", 100, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BedrockProperties("eu-west-1", "global.model", 100, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnboundedOutputConfiguration() {
        assertThatThrownBy(() -> new BedrockProperties("eu-west-1", "eu.model", 8193, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
