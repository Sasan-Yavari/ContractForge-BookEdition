package io.contractforge.backend.api.security;

import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import java.util.Collections;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;

/** Keeps JWT and Cognito details at the API/security adapter boundary. */
public final class CognitoAuthenticationAdapter implements Converter<Jwt, AbstractAuthenticationToken> {

    public static final String PROVIDER = "cognito";
    private final CognitoUserInfoLookup userInfoLookup;

    CognitoAuthenticationAdapter(CognitoUserInfoLookup userInfoLookup) {
        this.userInfoLookup = userInfoLookup;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Validated token has no subject");
        }
        CognitoUserInfo userInfo;
        try {
            userInfo = userInfoLookup.lookup(jwt.getTokenValue());
        } catch (RuntimeException exception) {
            throw invalidUserInfo();
        }
        if (userInfo == null || !subject.equals(userInfo.subject())) {
            throw invalidUserInfo();
        }
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(PROVIDER, subject,
                userInfo.email(), userInfo.emailVerified());
        return new UsernamePasswordAuthenticationToken(principal, jwt, Collections.emptyList());
    }

    private OAuth2AuthenticationException invalidUserInfo() {
        return new OAuth2AuthenticationException(new OAuth2Error(
                "invalid_token", "Unable to resolve the authenticated Cognito profile", null));
    }
}
