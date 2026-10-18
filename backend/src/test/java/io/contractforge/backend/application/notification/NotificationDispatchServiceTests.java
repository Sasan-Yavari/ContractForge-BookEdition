package io.contractforge.backend.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.port.RepositoryPorts.NotificationDeliveryRepository;
import io.contractforge.backend.domain.model.PersistenceModels.NotificationDelivery;
import io.contractforge.backend.domain.model.NotificationChannel;
import io.contractforge.backend.domain.model.NotificationDeliveryStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class NotificationDispatchServiceTests {
    private static final UUID DELIVERY_ID = UUID.fromString("71000000-0000-0000-0000-000000000001");
    private static final UUID NOTIFICATION_ID = UUID.fromString("71000000-0000-0000-0000-000000000002");

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesOnlyAfterTransactionCommit() {
        NotificationDeliveryRepository deliveries = Mockito.mock(NotificationDeliveryRepository.class);
        NotificationQueuePort queue = Mockito.mock(NotificationQueuePort.class);
        NotificationDispatchService service = new NotificationDispatchService(deliveries, queue,
                new NotificationEmailProperties(true), new NotificationEnvelopeSerializer(new ObjectMapper()));
        when(deliveries.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery(NotificationDeliveryStatus.QUEUED)));

        TransactionSynchronizationManager.initSynchronization();
        service.publishAfterCommit(DELIVERY_ID);

        verify(queue, never()).publish(any());
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
        verify(queue).publish(new NotificationQueuePort.NotificationDeliveryMessage(DELIVERY_ID, NOTIFICATION_ID, NotificationChannel.EMAIL));
    }

    @Test
    void doesNotPublishAfterTransactionRollback() {
        NotificationDeliveryRepository deliveries = Mockito.mock(NotificationDeliveryRepository.class);
        NotificationQueuePort queue = Mockito.mock(NotificationQueuePort.class);
        NotificationDispatchService service = new NotificationDispatchService(deliveries, queue,
                new NotificationEmailProperties(true), new NotificationEnvelopeSerializer(new ObjectMapper()));

        TransactionSynchronizationManager.initSynchronization();
        service.publishAfterCommit(DELIVERY_ID);
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCompletion(1));

        verify(queue, never()).publish(any());
    }

    @Test
    void envelopeContainsExactlyTheVersionOneFields() {
        NotificationDeliveryRepository deliveries = Mockito.mock(NotificationDeliveryRepository.class);
        NotificationDispatchService service = new NotificationDispatchService(deliveries,
                Mockito.mock(NotificationQueuePort.class), new NotificationEmailProperties(true),
                new NotificationEnvelopeSerializer(new ObjectMapper()));

        String envelope = service.envelope(new NotificationQueuePort.NotificationDeliveryMessage(
                DELIVERY_ID, NOTIFICATION_ID, NotificationChannel.EMAIL));

        String expectedEnvelope = String.format(
                "{\"schemaVersion\":%d,\"messageType\":\"%s\",\"deliveryId\":\"%s\","
                        + "\"notificationId\":\"%s\",\"channel\":\"%s\"}",
                NotificationQueueProtocol.SCHEMA_VERSION, NotificationQueueProtocol.MESSAGE_TYPE_DELIVERY,
                DELIVERY_ID, NOTIFICATION_ID, NotificationChannel.EMAIL);
        assertThat(envelope).isEqualTo(expectedEnvelope);
    }

    @Test
    void disabledEmailDoesNotPublishOrRecover() {
        NotificationDeliveryRepository deliveries = Mockito.mock(NotificationDeliveryRepository.class);
        NotificationQueuePort queue = Mockito.mock(NotificationQueuePort.class);
        NotificationDispatchService service = new NotificationDispatchService(deliveries, queue,
                new NotificationEmailProperties(false), new NotificationEnvelopeSerializer(new ObjectMapper()));

        service.publishAfterCommit(DELIVERY_ID);
        int recovered = service.recoverQueued();

        assertThat(recovered).isZero();
        verify(queue, never()).publish(any());
        verify(deliveries, never()).findQueued();
    }

    @Test
    void recoveryRepublishesOnlyDurableQueuedIntents() {
        NotificationDeliveryRepository deliveries = Mockito.mock(NotificationDeliveryRepository.class);
        NotificationQueuePort queue = Mockito.mock(NotificationQueuePort.class);
        NotificationDispatchService service = new NotificationDispatchService(deliveries, queue,
                new NotificationEmailProperties(true), new NotificationEnvelopeSerializer(new ObjectMapper()));
        when(deliveries.findQueued()).thenReturn(List.of(delivery(NotificationDeliveryStatus.QUEUED)));

        assertThat(service.recoverQueued()).isEqualTo(1);

        verify(queue).publish(new NotificationQueuePort.NotificationDeliveryMessage(DELIVERY_ID, NOTIFICATION_ID, NotificationChannel.EMAIL));
    }

    @Test
    void publicationFailureLeavesIntentRecoverable() {
        NotificationDeliveryRepository deliveries = Mockito.mock(NotificationDeliveryRepository.class);
        NotificationQueuePort queue = Mockito.mock(NotificationQueuePort.class);
        NotificationDispatchService service = new NotificationDispatchService(deliveries, queue,
                new NotificationEmailProperties(true), new NotificationEnvelopeSerializer(new ObjectMapper()));
        when(deliveries.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery(NotificationDeliveryStatus.QUEUED)));
        when(deliveries.findQueued()).thenReturn(List.of(delivery(NotificationDeliveryStatus.QUEUED)));
        doThrow(new IllegalStateException("queue unavailable")).doNothing().when(queue).publish(any());

        TransactionSynchronizationManager.initSynchronization();
        service.publishAfterCommit(DELIVERY_ID);
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
        assertThat(service.recoverQueued()).isEqualTo(1);

        verify(queue, Mockito.times(2)).publish(new NotificationQueuePort.NotificationDeliveryMessage(
                DELIVERY_ID, NOTIFICATION_ID, NotificationChannel.EMAIL));
    }

    @Test
    void terminalDeliveryIsNotPublished() {
        NotificationDeliveryRepository deliveries = Mockito.mock(NotificationDeliveryRepository.class);
        NotificationQueuePort queue = Mockito.mock(NotificationQueuePort.class);
        NotificationDispatchService service = new NotificationDispatchService(deliveries, queue,
                new NotificationEmailProperties(true), new NotificationEnvelopeSerializer(new ObjectMapper()));
        when(deliveries.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery(NotificationDeliveryStatus.FAILED)));

        TransactionSynchronizationManager.initSynchronization();
        service.publishAfterCommit(DELIVERY_ID);
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());

        verify(queue, never()).publish(any());
    }

    private NotificationDelivery delivery(NotificationDeliveryStatus status) {
        OffsetDateTime now = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        return new NotificationDelivery(DELIVERY_ID, NOTIFICATION_ID, NotificationChannel.EMAIL, status, "owner@example.test", 0,
                null, null, null, null, null, null, null, now, now);
    }
}
