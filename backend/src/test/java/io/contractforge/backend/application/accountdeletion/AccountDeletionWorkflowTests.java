package io.contractforge.backend.application.accountdeletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.port.ObjectStoragePort;
import io.contractforge.backend.application.port.UploadObjectStoragePort;
import io.contractforge.backend.domain.model.AccountDeletionJob;
import io.contractforge.backend.domain.model.AccountDeletionObjectKind;
import io.contractforge.backend.domain.model.AccountDeletionPhase;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountDeletionWorkflowTests {
    private static final UUID DELETION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID WORKER_TOKEN = UUID.randomUUID();
    private static final String SUBJECT = "subject-123";
    private static final String SECOND_SUBJECT = "subject-456";
    private static final String TRUSTED_KEY = "private/trusted/documents/" + UUID.randomUUID();
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-03T12:00:00Z");

    @Test
    void envelopeRoundTripsAndRejectsUnknownOrMalformedFields() {
        AccountDeletionEnvelopeSerializer serializer = new AccountDeletionEnvelopeSerializer(new ObjectMapper());
        String body = serializer.serialize(DELETION_ID);

        assertThat(serializer.parse(body)).isEqualTo(DELETION_ID);
        assertThatThrownBy(() -> serializer.parse(body.replace("}", ",\"email\":\"private@example.test\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> serializer.parse("{\"schemaVersion\":1,\"messageType\":\"ACCOUNT_DELETION\","
                + "\"deletionId\":\"not-a-uuid\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configuredIdentityFenceAndQueueVisibilityHaveExplicitSafetyMargin() {
        assertThat(new AccountDeletionProperties(Duration.ofMinutes(70), Duration.ofMinutes(5))).isNotNull();
        assertThatThrownBy(() -> new AccountDeletionProperties(Duration.ofMinutes(69), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new AccountDeletionQueueProperties("queue", 5, 20, Duration.ofSeconds(30),
                Duration.ofMinutes(4), Duration.ofMinutes(5))).isNotNull();
        assertThatThrownBy(() -> new AccountDeletionQueueProperties("queue", 5, 20, Duration.ofSeconds(30),
                Duration.ofMinutes(5), Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void objectFailureRemainsRetryableAndIsMarkedOnlyAfterStorageConfirmsDeletion() {
        AccountDeletionRepository repository = mock(AccountDeletionRepository.class);
        AccountDeletionWorkflowService workflow = mock(AccountDeletionWorkflowService.class);
        CognitoAccountAdministrationPort cognito = mock(CognitoAccountAdministrationPort.class);
        ObjectStoragePort trustedStorage = mock(ObjectStoragePort.class);
        UploadObjectStoragePort uploadStorage = mock(UploadObjectStoragePort.class);
        AccountDeletionQueueProperties queue = new AccountDeletionQueueProperties("queue", 5, 20,
                Duration.ofSeconds(30), Duration.ofMinutes(4), Duration.ofMinutes(5));
        AccountDeletionJob job = job(AccountDeletionPhase.DELETE_OBJECTS);
        var object = new AccountDeletionRepository.PendingObject(TRUSTED_KEY, AccountDeletionObjectKind.TRUSTED);
        when(repository.claim(eq(DELETION_ID), any(UUID.class), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(Optional.of(job));
        when(repository.findNextPendingObject(DELETION_ID)).thenReturn(Optional.of(object));
        doThrow(new IllegalStateException("transient storage failure")).doNothing()
                .when(trustedStorage).deletePrivateObject(TRUSTED_KEY);
        AccountDeletionWorker worker = new AccountDeletionWorker(repository, workflow, cognito, trustedStorage,
                uploadStorage, queue, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        assertThatThrownBy(() -> worker.process(DELETION_ID)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).markObjectDeleted(any(), any(), any());
        worker.process(DELETION_ID);

        verify(trustedStorage, org.mockito.Mockito.times(2)).deletePrivateObject(TRUSTED_KEY);
        verify(repository).markObjectDeleted(job, TRUSTED_KEY, NOW);
    }

    @Test
    void cognitoDisablePrecedesCapabilityWaitAndDeletionIsASeparateLaterStage() {
        AccountDeletionRepository repository = mock(AccountDeletionRepository.class);
        AccountDeletionWorkflowService workflow = mock(AccountDeletionWorkflowService.class);
        CognitoAccountAdministrationPort cognito = mock(CognitoAccountAdministrationPort.class);
        ObjectStoragePort trustedStorage = mock(ObjectStoragePort.class);
        UploadObjectStoragePort uploadStorage = mock(UploadObjectStoragePort.class);
        AccountDeletionQueueProperties queue = new AccountDeletionQueueProperties("queue", 5, 20,
                Duration.ofSeconds(30), Duration.ofMinutes(4), Duration.ofMinutes(5));
        AccountDeletionJob job = job(AccountDeletionPhase.DISABLE_COGNITO);
        when(repository.claim(eq(DELETION_ID), any(UUID.class), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(Optional.of(job));
        when(repository.findIdentities(USER_ID)).thenReturn(identities());
        AccountDeletionWorker worker = new AccountDeletionWorker(repository, workflow, cognito, trustedStorage,
                uploadStorage, queue, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        worker.process(DELETION_ID);

        verify(cognito).disable("cognito", SUBJECT);
        verify(cognito, never()).delete(any(), any());
        verify(repository).advance(job, AccountDeletionPhase.WAIT_UPLOAD_CAPABILITIES,
                job.uploadCapabilitiesExpireAt(), NOW);
    }

    @Test
    void immediateDisableUsesAnInitialPhaseClaimBeforeTheAcceptedResponse() {
        AccountDeletionRepository repository = mock(AccountDeletionRepository.class);
        AccountDeletionWorkflowService workflow = mock(AccountDeletionWorkflowService.class);
        CognitoAccountAdministrationPort cognito = mock(CognitoAccountAdministrationPort.class);
        ObjectStoragePort trustedStorage = mock(ObjectStoragePort.class);
        UploadObjectStoragePort uploadStorage = mock(UploadObjectStoragePort.class);
        AccountDeletionQueueProperties queue = new AccountDeletionQueueProperties("queue", 5, 20,
                Duration.ofSeconds(30), Duration.ofMinutes(4), Duration.ofMinutes(5));
        AccountDeletionJob job = job(AccountDeletionPhase.DISABLE_COGNITO);
        when(repository.claimInitialDisable(eq(DELETION_ID), any(UUID.class), any(OffsetDateTime.class),
                any(OffsetDateTime.class))).thenReturn(Optional.of(job));
        when(repository.findIdentities(USER_ID)).thenReturn(identities());
        AccountDeletionWorker worker = new AccountDeletionWorker(repository, workflow, cognito, trustedStorage,
                uploadStorage, queue, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        worker.disableCognitoImmediately(DELETION_ID);

        verify(cognito).disable("cognito", SUBJECT);
        verify(repository).advance(job, AccountDeletionPhase.WAIT_UPLOAD_CAPABILITIES,
                job.uploadCapabilitiesExpireAt(), NOW);
        verify(repository, never()).claim(any(), any(), any(), any());
    }

    @Test
    void disablesAndDeletesEveryIdentityMappingForTheUser() {
        AccountDeletionRepository repository = mock(AccountDeletionRepository.class);
        AccountDeletionWorkflowService workflow = mock(AccountDeletionWorkflowService.class);
        CognitoAccountAdministrationPort cognito = mock(CognitoAccountAdministrationPort.class);
        AccountDeletionQueueProperties queue = new AccountDeletionQueueProperties("queue", 5, 20,
                Duration.ofSeconds(30), Duration.ofMinutes(4), Duration.ofMinutes(5));
        AccountDeletionJob disableJob = job(AccountDeletionPhase.DISABLE_COGNITO);
        AccountDeletionJob deleteJob = job(AccountDeletionPhase.DELETE_COGNITO);
        List<AccountDeletionRepository.ExternalIdentity> identities = List.of(
                new AccountDeletionRepository.ExternalIdentity("cognito", SUBJECT),
                new AccountDeletionRepository.ExternalIdentity("cognito", SECOND_SUBJECT));
        when(repository.claim(eq(DELETION_ID), any(UUID.class), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(Optional.of(disableJob), Optional.of(deleteJob));
        when(repository.findIdentities(USER_ID)).thenReturn(identities);
        AccountDeletionWorker worker = new AccountDeletionWorker(repository, workflow, cognito, mock(ObjectStoragePort.class),
                mock(UploadObjectStoragePort.class), queue, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        worker.process(DELETION_ID);
        worker.process(DELETION_ID);

        verify(cognito).disable("cognito", SUBJECT);
        verify(cognito).disable("cognito", SECOND_SUBJECT);
        verify(cognito).delete("cognito", SUBJECT);
        verify(cognito).delete("cognito", SECOND_SUBJECT);
        verify(repository).advance(disableJob, AccountDeletionPhase.WAIT_UPLOAD_CAPABILITIES,
                disableJob.uploadCapabilitiesExpireAt(), NOW);
        verify(repository).advance(deleteJob, AccountDeletionPhase.WAIT_IDENTITY_FENCE,
                deleteJob.identityFenceUntil(), NOW);
    }

    @Test
    void missingIdentityMappingsKeepTheDeletionStageRetryable() {
        AccountDeletionRepository repository = mock(AccountDeletionRepository.class);
        AccountDeletionWorkflowService workflow = mock(AccountDeletionWorkflowService.class);
        CognitoAccountAdministrationPort cognito = mock(CognitoAccountAdministrationPort.class);
        AccountDeletionQueueProperties queue = new AccountDeletionQueueProperties("queue", 5, 20,
                Duration.ofSeconds(30), Duration.ofMinutes(4), Duration.ofMinutes(5));
        AccountDeletionJob job = job(AccountDeletionPhase.DISABLE_COGNITO);
        when(repository.claim(eq(DELETION_ID), any(UUID.class), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(Optional.of(job));
        when(repository.findIdentities(USER_ID)).thenReturn(List.of());
        AccountDeletionWorker worker = new AccountDeletionWorker(repository, workflow, cognito, mock(ObjectStoragePort.class),
                mock(UploadObjectStoragePort.class), queue, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        assertThatThrownBy(() -> worker.process(DELETION_ID)).isInstanceOf(IllegalStateException.class);

        verify(cognito, never()).disable(any(), any());
        verify(repository, never()).advance(any(), any(), any(), any());
    }

    @Test
    void duplicateDeliveryAfterTheJobWasRemovedDoesNotRepeatExternalCleanup() {
        AccountDeletionRepository repository = mock(AccountDeletionRepository.class);
        AccountDeletionWorkflowService workflow = mock(AccountDeletionWorkflowService.class);
        CognitoAccountAdministrationPort cognito = mock(CognitoAccountAdministrationPort.class);
        ObjectStoragePort trustedStorage = mock(ObjectStoragePort.class);
        UploadObjectStoragePort uploadStorage = mock(UploadObjectStoragePort.class);
        AccountDeletionQueueProperties queue = new AccountDeletionQueueProperties("queue", 5, 20,
                Duration.ofSeconds(30), Duration.ofMinutes(4), Duration.ofMinutes(5));
        when(repository.claim(eq(DELETION_ID), any(UUID.class), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(Optional.empty());
        AccountDeletionWorker worker = new AccountDeletionWorker(repository, workflow, cognito, trustedStorage,
                uploadStorage, queue, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        worker.process(DELETION_ID);

        verify(cognito, never()).disable(any(), any());
        verify(cognito, never()).delete(any(), any());
        verify(trustedStorage, never()).deletePrivateObject(any());
        verify(uploadStorage, never()).deleteQuarantineObject(any());
    }

    @Test
    void cognitoDeleteFailureLeavesTheStageRetryableUntilProviderSucceeds() {
        AccountDeletionRepository repository = mock(AccountDeletionRepository.class);
        AccountDeletionWorkflowService workflow = mock(AccountDeletionWorkflowService.class);
        CognitoAccountAdministrationPort cognito = mock(CognitoAccountAdministrationPort.class);
        ObjectStoragePort trustedStorage = mock(ObjectStoragePort.class);
        UploadObjectStoragePort uploadStorage = mock(UploadObjectStoragePort.class);
        AccountDeletionQueueProperties queue = new AccountDeletionQueueProperties("queue", 5, 20,
                Duration.ofSeconds(30), Duration.ofMinutes(4), Duration.ofMinutes(5));
        AccountDeletionJob job = job(AccountDeletionPhase.DELETE_COGNITO);
        when(repository.claim(eq(DELETION_ID), any(UUID.class), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(Optional.of(job));
        when(repository.findIdentities(USER_ID)).thenReturn(identities());
        doThrow(new IllegalStateException("transient Cognito failure")).doNothing()
                .when(cognito).delete("cognito", SUBJECT);
        AccountDeletionWorker worker = new AccountDeletionWorker(repository, workflow, cognito, trustedStorage,
                uploadStorage, queue, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        assertThatThrownBy(() -> worker.process(DELETION_ID)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).advance(any(), any(), any(), any());
        worker.process(DELETION_ID);

        verify(cognito, org.mockito.Mockito.times(2)).delete("cognito", SUBJECT);
        verify(repository).advance(job, AccountDeletionPhase.WAIT_IDENTITY_FENCE,
                job.identityFenceUntil(), NOW);
    }

    @Test
    void cognitoDisableFailureKeepsTheDurableJobImmediatelyRetryable() {
        AccountDeletionRepository repository = mock(AccountDeletionRepository.class);
        AccountDeletionWorkflowService workflow = mock(AccountDeletionWorkflowService.class);
        CognitoAccountAdministrationPort cognito = mock(CognitoAccountAdministrationPort.class);
        ObjectStoragePort trustedStorage = mock(ObjectStoragePort.class);
        UploadObjectStoragePort uploadStorage = mock(UploadObjectStoragePort.class);
        AccountDeletionQueueProperties queue = new AccountDeletionQueueProperties("queue", 5, 20,
                Duration.ofSeconds(30), Duration.ofMinutes(4), Duration.ofMinutes(5));
        AccountDeletionJob job = job(AccountDeletionPhase.DISABLE_COGNITO);
        when(repository.claimInitialDisable(eq(DELETION_ID), any(UUID.class), any(OffsetDateTime.class),
                any(OffsetDateTime.class)))
                .thenReturn(Optional.of(job));
        when(repository.findIdentities(USER_ID)).thenReturn(identities());
        doThrow(new IllegalStateException("transient Cognito failure")).when(cognito).disable("cognito", SUBJECT);
        AccountDeletionWorker worker = new AccountDeletionWorker(repository, workflow, cognito, trustedStorage,
                uploadStorage, queue, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        assertThatThrownBy(() -> worker.disableCognitoImmediately(DELETION_ID))
                .isInstanceOfSatisfying(AccountDeletionDisablePendingException.class,
                        pending -> assertThat(pending.retryAfterSeconds()).isEqualTo(5));

        verify(cognito).disable("cognito", SUBJECT);
        verify(repository).reschedule(job, NOW, NOW);
        verify(repository, never()).advance(any(), any(), any(), any());
    }

    @Test
    void immediateRequestPathCannotLeaseAJobThatAlreadyLeftInitialDisablePhase() {
        AccountDeletionRepository repository = mock(AccountDeletionRepository.class);
        AccountDeletionWorkflowService workflow = mock(AccountDeletionWorkflowService.class);
        CognitoAccountAdministrationPort cognito = mock(CognitoAccountAdministrationPort.class);
        ObjectStoragePort trustedStorage = mock(ObjectStoragePort.class);
        UploadObjectStoragePort uploadStorage = mock(UploadObjectStoragePort.class);
        AccountDeletionQueueProperties queue = new AccountDeletionQueueProperties("queue", 5, 20,
                Duration.ofSeconds(30), Duration.ofMinutes(4), Duration.ofMinutes(5));
        when(repository.claimInitialDisable(eq(DELETION_ID), any(UUID.class), any(OffsetDateTime.class),
                any(OffsetDateTime.class))).thenReturn(Optional.empty());
        when(repository.find(DELETION_ID)).thenReturn(Optional.of(job(AccountDeletionPhase.WAIT_UPLOAD_CAPABILITIES)));
        AccountDeletionWorker worker = new AccountDeletionWorker(repository, workflow, cognito, trustedStorage,
                uploadStorage, queue, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        worker.disableCognitoImmediately(DELETION_ID);

        verify(repository).claimInitialDisable(eq(DELETION_ID), any(UUID.class), eq(NOW),
                eq(NOW.plusMinutes(4)));
        verify(repository).find(DELETION_ID);
        verify(repository, never()).claim(any(), any(), any(), any());
        verify(cognito, never()).disable(any(), any());
    }

    @Test
    void immediateRequestRemainsPendingWhileAnotherWorkerOwnsInitialDisable() {
        AccountDeletionRepository repository = mock(AccountDeletionRepository.class);
        AccountDeletionWorkflowService workflow = mock(AccountDeletionWorkflowService.class);
        CognitoAccountAdministrationPort cognito = mock(CognitoAccountAdministrationPort.class);
        ObjectStoragePort trustedStorage = mock(ObjectStoragePort.class);
        UploadObjectStoragePort uploadStorage = mock(UploadObjectStoragePort.class);
        AccountDeletionQueueProperties queue = new AccountDeletionQueueProperties("queue", 5, 20,
                Duration.ofSeconds(30), Duration.ofMinutes(4), Duration.ofMinutes(5));
        when(repository.claimInitialDisable(eq(DELETION_ID), any(UUID.class), any(OffsetDateTime.class),
                any(OffsetDateTime.class))).thenReturn(Optional.empty());
        when(repository.find(DELETION_ID)).thenReturn(Optional.of(job(AccountDeletionPhase.DISABLE_COGNITO)));
        AccountDeletionWorker worker = new AccountDeletionWorker(repository, workflow, cognito, trustedStorage,
                uploadStorage, queue, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        assertThatThrownBy(() -> worker.disableCognitoImmediately(DELETION_ID))
                .isInstanceOfSatisfying(AccountDeletionDisablePendingException.class,
                        pending -> assertThat(pending.retryAfterSeconds()).isEqualTo(5));

        verify(repository).find(DELETION_ID);
        verify(cognito, never()).disable(any(), any());
        verify(repository, never()).reschedule(any(), any(), any());
    }

    private AccountDeletionJob job(AccountDeletionPhase phase) {
        return new AccountDeletionJob(DELETION_ID, USER_ID, phase,
                NOW.minusMinutes(1), NOW.plusMinutes(70), NOW.minusMinutes(1), WORKER_TOKEN);
    }

    private List<AccountDeletionRepository.ExternalIdentity> identities() {
        return List.of(new AccountDeletionRepository.ExternalIdentity("cognito", SUBJECT));
    }
}
