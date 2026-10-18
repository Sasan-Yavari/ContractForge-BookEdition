package io.contractforge.backend.application.accountdeletion;

import java.util.UUID;

/** Queue boundary for durable account-deletion jobs. */
public interface AccountDeletionQueuePort {
    void publish(UUID deletionId);

    boolean configured();
}
