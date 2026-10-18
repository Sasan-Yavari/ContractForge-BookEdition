package io.contractforge.backend.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class CognitoSecurityConfigurationTests {

    private static final String ISSUER = "https://cognito.example.test";
    private static final String CLIENT_ID = "client-123";

    @Test
    void productionValidatorCompositionValidatesIssuerTimeAndCognitoClaims() {
        var validator = SecurityConfiguration.tokenValidator(
                new CognitoSecurityProperties(ISSUER, CLIENT_ID, null, "https://cognito.example.test/oauth2/userInfo"));

        assertAccepted(validator, token("access", CLIENT_ID, ISSUER, List.of("different-resource"),
                Instant.now().plusSeconds(300)));
        assertRejected(validator, token("id", CLIENT_ID, ISSUER, List.of(), Instant.now().plusSeconds(300)));
        assertRejected(validator, token("access", "other-client", ISSUER, List.of(), Instant.now().plusSeconds(300)));
        assertRejected(validator, token("access", CLIENT_ID, "https://wrong.example.test", List.of(),
                Instant.now().plusSeconds(300)));
        assertRejected(validator, token("access", CLIENT_ID, ISSUER, List.of(), Instant.now().minusSeconds(120)));
    }

    @Test
    void audienceIsValidatedOnlyWhenSeparateResourceAudienceIsConfigured() {
        var validator = SecurityConfiguration.tokenValidator(
                new CognitoSecurityProperties(ISSUER, CLIENT_ID, "contractforge-api",
                        "https://cognito.example.test/oauth2/userInfo"));

        assertAccepted(validator, token("access", CLIENT_ID, ISSUER, List.of("contractforge-api"),
                Instant.now().plusSeconds(300)));
        assertRejected(validator, token("access", CLIENT_ID, ISSUER, List.of("other-resource"),
                Instant.now().plusSeconds(300)));
        assertRejected(validator, token("access", CLIENT_ID, ISSUER, List.of(), Instant.now().plusSeconds(300)));
    }

    private Jwt token(String tokenUse, String clientId, String issuer, List<String> audience, Instant expiresAt) {
        Instant issuedAt = expiresAt.isBefore(Instant.now())
                ? expiresAt.minusSeconds(60) : Instant.now().minusSeconds(60);
        return new Jwt("test-token", issuedAt, expiresAt, Map.of("alg", "none"),
                Map.of("iss", issuer, "sub", "subject-123", "token_use", tokenUse,
                        "client_id", clientId, "aud", audience));
    }

    private void assertAccepted(org.springframework.security.oauth2.core.OAuth2TokenValidator<Jwt> validator,
                                Jwt token) {
        assertThat(validator.validate(token).hasErrors()).isFalse();
    }

    private void assertRejected(org.springframework.security.oauth2.core.OAuth2TokenValidator<Jwt> validator,
                                Jwt token) {
        OAuth2TokenValidatorResult result = validator.validate(token);
        assertThat(result.hasErrors()).isTrue();
    }
}
