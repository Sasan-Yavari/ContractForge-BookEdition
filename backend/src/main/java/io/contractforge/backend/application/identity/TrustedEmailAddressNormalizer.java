package io.contractforge.backend.application.identity;

import java.util.Locale;
import java.util.Optional;

/** Normalizes only an email address asserted as verified by the trusted identity provider. */
public final class TrustedEmailAddressNormalizer {

    private TrustedEmailAddressNormalizer() {
    }

    public static Optional<String> normalize(String email, boolean verified) {
        if (!verified || email == null) {
            return Optional.empty();
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > 320
                || !normalized.matches("^[^@\\s]+@[^@\\s]+$")) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }
}
