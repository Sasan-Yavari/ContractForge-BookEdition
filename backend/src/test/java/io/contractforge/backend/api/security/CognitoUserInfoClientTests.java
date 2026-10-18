package io.contractforge.backend.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class CognitoUserInfoClientTests {

    private static final String USER_INFO_URI = "https://cognito.example.test/oauth2/userInfo";

    @Test
    void sendsValidatedAccessTokenAndReadsCognitoStringEmailVerifiedAttribute() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(USER_INFO_URI))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer opaque-access-token"))
                .andRespond(withSuccess(
                        "{\"sub\":\"cognito-subject\",\"email\":\"owner@example.test\",\"email_verified\":\"true\"}",
                        MediaType.APPLICATION_JSON));

        CognitoUserInfo userInfo = new CognitoUserInfoClient(USER_INFO_URI, builder.build())
                .lookup("opaque-access-token");

        assertThat(userInfo).isEqualTo(new CognitoUserInfo("cognito-subject", "owner@example.test", true));
        server.verify();
    }

    @Test
    void requiresAnHttpsUserInfoEndpointAtStartup() {
        assertThatThrownBy(() -> new CognitoUserInfoClient("http://cognito.example.test/oauth2/userInfo",
                RestClient.builder().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valid HTTPS URI");
    }

    @Test
    void requiresTheUserInfoEndpointToBeConfigured() {
        assertThatThrownBy(() -> new CognitoUserInfoClient("", RestClient.builder().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be configured");
    }

    @Test
    void doesNotTrustAnUnverifiedEmail() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(USER_INFO_URI))
                .andRespond(withSuccess(
                        "{\"sub\":\"cognito-subject\",\"email\":\"owner@example.test\",\"email_verified\":\"false\"}",
                        MediaType.APPLICATION_JSON));

        CognitoUserInfo userInfo = new CognitoUserInfoClient(USER_INFO_URI, builder.build())
                .lookup("opaque-access-token");

        assertThat(userInfo.emailVerified()).isFalse();
        server.verify();
    }
}
