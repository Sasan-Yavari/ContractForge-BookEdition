package io.contractforge.backend.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        classes = CognitoAwsSecurityContextTests.AwsSecurityTestApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "spring.datasource.url=jdbc:postgresql://localhost:5432/contractforge",
                "spring.datasource.driver-class-name=org.postgresql.Driver",
                "spring.datasource.hikari.data-source-properties.wrapperPlugins=",
                "spring.datasource.hikari.data-source-properties.secretsManagerSecretId=",
                "contractforge.security.cognito.issuer-uri=https://cognito.example.test",
                "contractforge.security.cognito.client-id=client-123",
                "contractforge.security.cognito.user-info-uri=https://cognito.example.test/oauth2/userInfo"
        })
@ActiveProfiles("aws")
class CognitoAwsSecurityContextTests {

    @Autowired
    private CognitoUserInfoLookup cognitoUserInfoLookup;

    @Autowired
    private RestClient.Builder restClientBuilder;

    @MockitoBean(name = "jwtDecoder")
    private JwtDecoder jwtDecoder;

    @Test
    void awsSecurityConfigurationStartsWithAutoConfiguredRestClientBuilder() {
        assertThat(restClientBuilder).isNotNull();
        assertThat(cognitoUserInfoLookup).isInstanceOf(CognitoUserInfoClient.class);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(SecurityConfiguration.class)
    static class AwsSecurityTestApplication {
    }
}
