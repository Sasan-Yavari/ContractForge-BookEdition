package io.contractforge.backend.application.security;

/** The validated external identity exposed to application code. */
public record AuthenticatedPrincipal(String provider, String providerSubject, String email,
                                     boolean emailVerified) {
    public AuthenticatedPrincipal(String provider, String providerSubject) {
        this(provider, providerSubject, null, false);
    }
}
