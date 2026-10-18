package io.contractforge.backend.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contractforge.security.cognito")
public record CognitoSecurityProperties(String issuerUri, String clientId, String resourceAudience,
                                        String userInfoUri) {
}
