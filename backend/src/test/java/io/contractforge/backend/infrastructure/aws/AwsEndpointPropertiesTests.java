package io.contractforge.backend.infrastructure.aws;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AwsEndpointPropertiesTests {
    @Test
    void usesPublicEndpointForBrowserVisiblePresignedUrls() {
        var properties = new AwsEndpointProperties("http://localstack:4566", "http://localhost:4566");

        assertThat(properties.configured()).isTrue();
        assertThat(properties.presignEndpoint()).isEqualTo("http://localhost:4566");
    }

    @Test
    void leavesProductionEndpointUnsetByDefault() {
        var properties = new AwsEndpointProperties("", "");

        assertThat(properties.configured()).isFalse();
    }
}
