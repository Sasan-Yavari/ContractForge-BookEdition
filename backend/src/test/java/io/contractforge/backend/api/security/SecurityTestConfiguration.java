package io.contractforge.backend.api.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.context.annotation.Profile;

@TestConfiguration(proxyBeanMethods = false)
public class SecurityTestConfiguration {

    @Bean
    @Primary
    @Profile("security-test")
    CognitoUserInfoLookup testCognitoUserInfoLookup() {
        return accessToken -> {
            String subject = accessToken.startsWith("access-")
                    ? accessToken.substring("access-".length()) : "subject-123";
            return new CognitoUserInfo(subject, subject + "@example.test", true);
        };
    }

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("https://cognito.example.test"),
                new CognitoAccessTokenValidator("client-123", null));
        return tokenValue -> {
            if ("malformed".equals(tokenValue)) {
                throw new JwtValidationException("Malformed test token",
                        List.of(new OAuth2Error("invalid_token", "Malformed test token", null)));
            }
            Instant now = Instant.now();
            String tokenUse = "id".equals(tokenValue) ? "id" : "access";
            String issuer = "wrong-issuer".equals(tokenValue)
                    ? "https://wrong.example.test" : "https://cognito.example.test";
            String clientId = "wrong-client".equals(tokenValue)
                    ? "other-client" : "client-123";
            String subject = tokenValue.startsWith("access-")
                    ? tokenValue.substring("access-".length()) : "subject-123";
            List<String> audience = "wrong-audience".equals(tokenValue)
                    ? List.of("other-audience") : List.of();
            Jwt jwt = new Jwt(tokenValue, now.minusSeconds(60), now.plusSeconds(300),
                    Map.of("alg", "none"), Map.of("iss", issuer, "sub", subject,
                    "token_use", tokenUse, "client_id", clientId, "aud", audience,
                    "scope", "openid email"));
            OAuth2TokenValidatorResult result = validator.validate(jwt);
            if (result.hasErrors()) {
                throw new JwtValidationException("Invalid test token", result.getErrors());
            }
            return jwt;
        };
    }
}
