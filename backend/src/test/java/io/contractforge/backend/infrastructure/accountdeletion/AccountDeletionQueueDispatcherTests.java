package io.contractforge.backend.infrastructure.accountdeletion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.contractforge.backend.application.accountdeletion.AccountDeletionQueuePort;
import io.contractforge.backend.application.accountdeletion.AccountDeletionQueueProperties;
import io.contractforge.backend.application.accountdeletion.AccountDeletionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountDeletionQueueDispatcherTests {
    private static final UUID DELETION_ID = UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-03T12:00:00Z");

    @Test
    void queuePublishFailureReleasesDispatchLeaseForDurableRetry() {
        AccountDeletionRepository repository = mock(AccountDeletionRepository.class);
        AccountDeletionQueuePort queue = mock(AccountDeletionQueuePort.class);
        AccountDeletionQueueProperties properties = properties();
        when(queue.configured()).thenReturn(true);
        when(repository.findReadyToDispatch(NOW, properties.dispatchBatchSize())).thenReturn(List.of(DELETION_ID));
        when(repository.claimDispatch(eq(DELETION_ID), any(UUID.class), eq(NOW), eq(NOW.plusSeconds(30))))
                .thenReturn(true);
        doThrow(new IllegalStateException("transient SQS failure")).when(queue).publish(DELETION_ID);
        AccountDeletionQueueDispatcher dispatcher = new AccountDeletionQueueDispatcher(repository, queue, properties,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        dispatcher.dispatch();

        verify(repository).releaseDispatch(eq(DELETION_ID), any(UUID.class));
        verify(repository, never()).markEnqueued(any(), any(), any());
    }

    @Test
    void successfulQueuePublishMarksTheDurableJobEnqueued() {
        AccountDeletionRepository repository = mock(AccountDeletionRepository.class);
        AccountDeletionQueuePort queue = mock(AccountDeletionQueuePort.class);
        AccountDeletionQueueProperties properties = properties();
        when(queue.configured()).thenReturn(true);
        when(repository.findReadyToDispatch(NOW, properties.dispatchBatchSize())).thenReturn(List.of(DELETION_ID));
        when(repository.claimDispatch(eq(DELETION_ID), any(UUID.class), eq(NOW), eq(NOW.plusSeconds(30))))
                .thenReturn(true);
        AccountDeletionQueueDispatcher dispatcher = new AccountDeletionQueueDispatcher(repository, queue, properties,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        dispatcher.dispatch();

        verify(queue).publish(DELETION_ID);
        verify(repository).markEnqueued(eq(DELETION_ID), any(UUID.class), eq(NOW));
    }

    private AccountDeletionQueueProperties properties() {
        return new AccountDeletionQueueProperties("queue", 5, 20, Duration.ofSeconds(30),
                Duration.ofMinutes(4), Duration.ofMinutes(5));
    }
}
