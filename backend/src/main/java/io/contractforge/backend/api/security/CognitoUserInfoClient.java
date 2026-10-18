package io.contractforge.backend.api.security;

import java.net.URI;
import org.springframework.web.client.RestClient;

/** Calls Cognito UserInfo over TLS so user attributes are not inferred from access-token claims. */
final class CognitoUserInfoClient implements CognitoUserInfoLookup {

    private final RestClient restClient;
    private final URI userInfoUri;

    CognitoUserInfoClient(String configuredUserInfoUri, RestClient restClient) {
        this.userInfoUri = validateUserInfoUri(configuredUserInfoUri);
        this.restClient = restClient;
    }

    @Override
    public CognitoUserInfo lookup(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("A validated Cognito access token is required");
        }

        UserInfoResponse response = restClient.get()
                .uri(userInfoUri)
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(UserInfoResponse.class);
        if (response == null || response.sub() == null || response.sub().isBlank()) {
            throw new IllegalStateException("Cognito UserInfo response has no subject");
        }
        return new CognitoUserInfo(response.sub(), response.email(), "true".equalsIgnoreCase(response.emailVerified()));
    }

    private static URI validateUserInfoUri(String configuredUri) {
        if (configuredUri == null || configuredUri.isBlank()) {
            throw new IllegalStateException("Cognito UserInfo URI must be configured");
        }
        URI uri;
        try {
            uri = URI.create(configuredUri);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Cognito UserInfo URI must be a valid HTTPS URI");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalStateException("Cognito UserInfo URI must be a valid HTTPS URI");
        }
        return uri;
    }

    private record UserInfoResponse(String sub, String email, String email_verified) {

        String emailVerified() {
            return email_verified;
        }
    }
}
