package io.contractforge.backend.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

class CognitoAuthenticationAdapterTests {

    @Test
    void resolvesTrustedEmailFromUserInfoWhenAccessTokenContainsNoEmailAttributes() {
        Jwt accessToken = accessToken("opaque-access-token", "cognito-subject");
        assertThat(accessToken.hasClaim("email")).isFalse();
        assertThat(accessToken.hasClaim("email_verified")).isFalse();
        CognitoUserInfoLookup userInfoLookup = token -> {
            assertThat(token).isEqualTo("opaque-access-token");
            return new CognitoUserInfo("cognito-subject", "owner@example.test", true);
        };
        CognitoAuthenticationAdapter adapter = new CognitoAuthenticationAdapter(userInfoLookup);

        AuthenticatedPrincipal principal = (AuthenticatedPrincipal) adapter.convert(accessToken).getPrincipal();

        assertThat(principal).isEqualTo(new AuthenticatedPrincipal(
                CognitoAuthenticationAdapter.PROVIDER, "cognito-subject", "owner@example.test", true));
    }

    @Test
    void rejectsUserInfoForAnotherSubject() {
        CognitoUserInfoLookup userInfoLookup = token ->
                new CognitoUserInfo("different-subject", "attacker@example.test", true);
        CognitoAuthenticationAdapter adapter = new CognitoAuthenticationAdapter(userInfoLookup);

        assertThatThrownBy(() -> adapter.convert(accessToken("opaque-access-token", "cognito-subject")))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("Unable to resolve the authenticated Cognito profile")
                .hasMessageNotContaining("attacker@example.test");
    }

    @Test
    void failsAuthenticationWhenUserInfoIsUnavailable() {
        CognitoUserInfoLookup userInfoLookup = token -> {
            throw new IllegalStateException("provider unavailable");
        };
        CognitoAuthenticationAdapter adapter = new CognitoAuthenticationAdapter(userInfoLookup);

        assertThatThrownBy(() -> adapter.convert(accessToken("opaque-access-token", "cognito-subject")))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("Unable to resolve the authenticated Cognito profile")
                .hasMessageNotContaining("provider unavailable");
    }

    private Jwt accessToken(String token, String subject) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .issuer("https://cognito.example.test")
                .subject(subject)
                .issuedAt(now.minusSeconds(30))
                .expiresAt(now.plusSeconds(300))
                .claim("token_use", "access")
                .claim("client_id", "client-123")
                .claim("scope", "openid email")
                .build();
    }
}
