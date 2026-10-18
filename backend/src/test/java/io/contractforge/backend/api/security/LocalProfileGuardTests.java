package io.contractforge.backend.api.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LocalProfileGuardTests {
    @Test
    void localProfileRequiresExplicitOptIn() {
        assertThatThrownBy(() -> new LocalProfileGuard(false))
                .isInstanceOf(IllegalStateException.class);
    }
}
