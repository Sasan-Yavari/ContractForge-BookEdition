package io.contractforge.backend.api.security;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

final class CognitoAccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final String ERROR_CODE = "invalid_token";
    private final String expectedClientId;
    private final String expectedResourceAudience;

    CognitoAccessTokenValidator(String expectedClientId, String expectedResourceAudience) {
        this.expectedClientId = expectedClientId;
        this.expectedResourceAudience = expectedResourceAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (!"access".equals(token.getClaimAsString("token_use"))) {
            return failure("The token is not a Cognito access token");
        }
        if (expectedClientId == null || expectedClientId.isBlank()
                || !expectedClientId.equals(token.getClaimAsString("client_id"))) {
            return failure("The token client is not configured for this API");
        }
        if (expectedResourceAudience != null && !expectedResourceAudience.isBlank()) {
            List<String> audience = token.getAudience();
            if (audience.isEmpty() || !audience.contains(expectedResourceAudience)) {
                return failure("The token audience is not configured for this API");
            }
        }
        return OAuth2TokenValidatorResult.success();
    }

    private OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(ERROR_CODE, description, null));
    }
}
