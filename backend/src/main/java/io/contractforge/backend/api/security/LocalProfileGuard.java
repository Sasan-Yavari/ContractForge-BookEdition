package io.contractforge.backend.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Prevents an accidentally activated local profile from starting without explicit local opt-in. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
final class LocalProfileGuard {
    LocalProfileGuard(@Value("${contractforge.local.auth-enabled:false}") boolean enabled) {
        if (!enabled) throw new IllegalStateException("The local profile requires contractforge.local.auth-enabled=true");
    }
}
