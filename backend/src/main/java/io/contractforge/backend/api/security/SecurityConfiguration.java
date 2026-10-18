package io.contractforge.backend.api.security;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;

@Configuration
@Profile({"!test & !integration & !local", "security-test"})
@EnableConfigurationProperties(CognitoSecurityProperties.class)
public class SecurityConfiguration {

    private static final Duration COGNITO_USER_INFO_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration COGNITO_USER_INFO_READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                                CognitoAuthenticationAdapter authenticationAdapter)
            throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/v1/health").permitAll()
                        .requestMatchers("/v1/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationAdapter)));
        return http.build();
    }

    @Bean
    CognitoAuthenticationAdapter cognitoAuthenticationAdapter(CognitoUserInfoLookup userInfoLookup) {
        return new CognitoAuthenticationAdapter(userInfoLookup);
    }

    @Bean
    @Profile("!security-test")
    CognitoUserInfoLookup cognitoUserInfoLookup(CognitoSecurityProperties properties,
                                               RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(COGNITO_USER_INFO_CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(COGNITO_USER_INFO_READ_TIMEOUT);
        RestClient userInfoRestClient = restClientBuilder.requestFactory(requestFactory).build();
        return new CognitoUserInfoClient(properties.userInfoUri(), userInfoRestClient);
    }

    @Bean
    @Profile("!security-test")
    JwtDecoder jwtDecoder(CognitoSecurityProperties properties) {
        if (properties.issuerUri() == null || properties.issuerUri().isBlank()
                || properties.clientId() == null || properties.clientId().isBlank()) {
            throw new IllegalStateException("Cognito issuer URI and client ID must be configured");
        }
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(properties.issuerUri());
        decoder.setJwtValidator(tokenValidator(properties));
        return decoder;
    }

    static DelegatingOAuth2TokenValidator<Jwt> tokenValidator(CognitoSecurityProperties properties) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuerUri()),
                new CognitoAccessTokenValidator(properties.clientId(), properties.resourceAudience()));
    }
}
