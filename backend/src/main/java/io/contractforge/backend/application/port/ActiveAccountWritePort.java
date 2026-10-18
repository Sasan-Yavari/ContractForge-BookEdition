package io.contractforge.backend.application.port;

import java.util.UUID;

/** Locks an active account row for the surrounding transaction before an owner-scoped write. */
@FunctionalInterface
public interface ActiveAccountWritePort {
    boolean lockActiveUser(UUID userId);
}
