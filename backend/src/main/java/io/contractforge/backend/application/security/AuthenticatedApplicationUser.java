package io.contractforge.backend.application.security;

import java.util.UUID;

/** Server-resolved application identity used by future use cases for ownership decisions. */
public record AuthenticatedApplicationUser(UUID userId) {
}
