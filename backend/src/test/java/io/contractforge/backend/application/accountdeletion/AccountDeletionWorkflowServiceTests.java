package io.contractforge.backend.application.accountdeletion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.contractforge.backend.application.port.RepositoryPorts.UserRepository;
import io.contractforge.backend.domain.model.AccountDeletionJob;
import io.contractforge.backend.domain.model.AccountDeletionPhase;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountDeletionWorkflowServiceTests {
    private final AccountDeletionRepository deletions = mock(AccountDeletionRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AccountDeletionTombstonePort tombstones = mock(AccountDeletionTombstonePort.class);
    private final AccountDeletionObjectManifest objectManifest = mock(AccountDeletionObjectManifest.class);
    private final AccountDeletionWorkflowService workflow = new AccountDeletionWorkflowService(deletions, users,
            tombstones, objectManifest, Clock.systemUTC());

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void marksExternalTombstoneCompleteOnlyAfterTheRelationalCommitCallback() {
        UUID operationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AccountDeletionJob job = completedJob(operationId, userId);
        TransactionSynchronizationManager.initSynchronization();

        workflow.complete(job);

        verify(deletions).complete(any(), any(), any(), any());
        verify(tombstones, never()).complete(any(), any(), any());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(tombstones).complete(eq(operationId), eq(userId), any());
    }

    @Test
    void retriesTombstoneCompletionOnlyAfterApplicationDeletionStateIsAbsent() {
        UUID operationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AccountDeletionTombstone tombstone = new AccountDeletionTombstone(operationId, userId, "source-resource",
                List.of("v1_fingerprint"), AccountDeletionTombstone.State.IN_PROGRESS,
                OffsetDateTime.parse("2026-09-01T12:00:00Z"), null, null, null, 0);
        when(tombstones.find(operationId)).thenReturn(Optional.of(tombstone));
        when(deletions.find(operationId)).thenReturn(Optional.empty());
        when(deletions.findIdentities(userId)).thenReturn(List.of());
        when(users.findById(userId)).thenReturn(Optional.empty());

        workflow.completeTombstoneIfApplicationDataIsPurged(operationId);

        verify(tombstones).complete(eq(operationId), eq(userId), any());
    }

    @Test
    void leavesTombstoneInProgressWhileAnApplicationDeletionJobRemains() {
        UUID operationId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AccountDeletionTombstone tombstone = new AccountDeletionTombstone(operationId, userId, "source-resource",
                List.of("v1_fingerprint"), AccountDeletionTombstone.State.IN_PROGRESS,
                OffsetDateTime.parse("2026-09-01T12:00:00Z"), null, null, null, 0);
        when(tombstones.find(operationId)).thenReturn(Optional.of(tombstone));
        when(deletions.find(operationId)).thenReturn(Optional.of(completedJob(operationId, userId)));

        workflow.completeTombstoneIfApplicationDataIsPurged(operationId);

        verify(tombstones, never()).complete(any(), any(), any());
    }

    private static AccountDeletionJob completedJob(UUID operationId, UUID userId) {
        OffsetDateTime now = OffsetDateTime.parse("2026-09-01T12:00:00Z");
        return new AccountDeletionJob(operationId, userId, AccountDeletionPhase.WAIT_IDENTITY_FENCE,
                now, now, now, UUID.randomUUID());
    }
}
