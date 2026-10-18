package io.contractforge.backend.infrastructure.aws;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional endpoint overrides used only by an explicitly configured local AWS emulator. */
@ConfigurationProperties(prefix = "contractforge.aws")
public record AwsEndpointProperties(String endpoint, String publicEndpoint) {
    public boolean configured() {
        return endpoint != null && !endpoint.isBlank();
    }

    public String presignEndpoint() {
        return publicEndpoint != null && !publicEndpoint.isBlank() ? publicEndpoint : endpoint;
    }
}
