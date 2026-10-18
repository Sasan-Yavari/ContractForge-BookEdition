package io.contractforge.backend.api.security;

import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "contractforge.local.auth-enabled", havingValue = "true")
final class LocalSecurityConfiguration {
    private static final Map<String, AuthenticatedPrincipal> TOKENS = Map.of(
            "local-owner", new AuthenticatedPrincipal("local", "owner", "owner@example.test", true),
            "local-other", new AuthenticatedPrincipal("local", "other", "other@example.test", true));

    @Bean
    SecurityFilterChain localSecurityFilterChain(HttpSecurity http,
                                                   @Value("${contractforge.local.auth-enabled}") boolean enabled)
            throws Exception {
        if (!enabled) throw new IllegalStateException("Local authentication requires explicit opt-in");
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.requestMatchers("/v1/health").permitAll()
                        .requestMatchers("/v1/**").authenticated().anyRequest().permitAll())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, failure) ->
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .addFilterBefore(new LocalBearerAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    static final class LocalBearerAuthenticationFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                AuthenticatedPrincipal principal = TOKENS.get(header.substring("Bearer ".length()));
                if (principal != null) {
                    var authentication = new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
            chain.doFilter(request, response);
        }
    }
}
