package io.contractforge.backend.domain.model;

/** Durable stages of the recoverable account-deletion workflow. */
public enum AccountDeletionPhase {
    DISABLE_COGNITO,
    WAIT_UPLOAD_CAPABILITIES,
    PURGE_RELATIONAL,
    DELETE_OBJECTS,
    DELETE_COGNITO,
    WAIT_IDENTITY_FENCE
}
