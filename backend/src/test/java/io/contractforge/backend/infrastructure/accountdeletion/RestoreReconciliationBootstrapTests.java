package io.contractforge.backend.infrastructure.accountdeletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.contractforge.backend.application.accountdeletion.AccountDeletionObjectManifest;
import io.contractforge.backend.application.accountdeletion.AccountDeletionProperties;
import io.contractforge.backend.application.accountdeletion.AccountDeletionQueueProperties;
import io.contractforge.backend.application.accountdeletion.AccountDeletionRepository;
import io.contractforge.backend.application.accountdeletion.AccountDeletionTombstone;
import io.contractforge.backend.application.accountdeletion.AccountDeletionWorker;
import io.contractforge.backend.application.accountdeletion.AccountDeletionWorkflowService;
import io.contractforge.backend.application.accountdeletion.CognitoAccountAdministrationPort;
import io.contractforge.backend.application.port.ObjectStoragePort;
import io.contractforge.backend.application.port.RepositoryPorts.UserRepository;
import io.contractforge.backend.application.port.UploadObjectStoragePort;
import io.contractforge.backend.domain.model.AccountDeletionJob;
import io.contractforge.backend.domain.model.AccountDeletionObjectKind;
import io.contractforge.backend.domain.model.AccountDeletionPhase;
import io.contractforge.backend.domain.model.PersistenceModels.User;
import io.contractforge.backend.domain.model.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RestoreReconciliationBootstrapTests {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-04T12:00:00Z");

    @Test
    void restoredActiveAccountCapturesTrustedAndUploadObjectsBeforeRetryableCleanup() {
        UUID deletionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID uploadId = UUID.randomUUID();
        String documentKey = "private/trusted/documents/restored-document";
        String quarantineKey = "quarantine/incoming/restored-upload";
        String uploadTrustedKey = "private/trusted/documents/" + uploadId;

        AccountDeletionRepository deletions = mock(AccountDeletionRepository.class);
        UserRepository users = mock(UserRepository.class);
        UploadObjectStoragePort uploadStorage = mock(UploadObjectStoragePort.class);
        AccountDeletionObjectManifest objectManifest = new AccountDeletionObjectManifest(deletions, uploadStorage);
        Clock clock = Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
        AccountDeletionTombstone tombstone = new AccountDeletionTombstone(deletionId, userId, "source-resource",
                List.of("v1_fingerprint"), AccountDeletionTombstone.State.COMPLETED,
                NOW.minusDays(2), NOW.minusDays(1), null, null, 0);
        when(deletions.find(deletionId)).thenReturn(Optional.empty());
        when(users.findById(userId)).thenReturn(Optional.of(new User(userId, UserStatus.ACTIVE,
                NOW.minusDays(30), NOW.minusDays(30))));
        when(deletions.begin(eq(userId), eq(deletionId), eq(NOW), eq(NOW), eq(Duration.ZERO)))
                .thenReturn(Optional.of(new AccountDeletionRepository.BeginResult(deletionId, true)));
        when(deletions.findOwnedTrustedObjectKeys(userId)).thenReturn(List.of(documentKey));
        when(deletions.findOwnedUploads(userId)).thenReturn(List.of(
                new AccountDeletionRepository.OwnedUpload(uploadId, quarantineKey)));
        when(uploadStorage.trustedKeyFor(uploadId)).thenReturn(uploadTrustedKey);

        AtomicReference<List<AccountDeletionRepository.PendingObject>> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(List.copyOf(invocation.getArgument(1)));
            return null;
        }).when(deletions).captureObjects(eq(deletionId), anyList());

        RestoreReconciliationBootstrap bootstrap = new RestoreReconciliationBootstrap(deletions, users,
                new AccountDeletionProperties(Duration.ofMinutes(70), Duration.ZERO), objectManifest, clock);

        assertThat(bootstrap.ensureJob(tombstone)).contains(deletionId);
        assertThat(captured.get()).containsExactly(
                new AccountDeletionRepository.PendingObject(documentKey, AccountDeletionObjectKind.TRUSTED),
                new AccountDeletionRepository.PendingObject(quarantineKey, AccountDeletionObjectKind.QUARANTINE),
                new AccountDeletionRepository.PendingObject(uploadTrustedKey, AccountDeletionObjectKind.TRUSTED));

        AccountDeletionJob job = new AccountDeletionJob(deletionId, userId, AccountDeletionPhase.DELETE_OBJECTS,
                NOW, NOW, NOW, UUID.randomUUID());
        List<AccountDeletionRepository.PendingObject> pending = new ArrayList<>(captured.get());
        Set<String> trustedObjects = new HashSet<>(List.of(documentKey, uploadTrustedKey));
        Set<String> quarantineObjects = new HashSet<>(List.of(quarantineKey));
        List<String> trustedDeleteAttempts = new ArrayList<>();
        List<String> quarantineDeleteAttempts = new ArrayList<>();
        ObjectStoragePort trustedStorage = mock(ObjectStoragePort.class);
        UploadObjectStoragePort restoredUploadStorage = mock(UploadObjectStoragePort.class);
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            trustedDeleteAttempts.add(key);
            trustedObjects.remove(key); // Deleting the same already-absent S3 key is safe.
            return null;
        }).when(trustedStorage).deletePrivateObject(anyString());
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            quarantineDeleteAttempts.add(key);
            quarantineObjects.remove(key);
            return null;
        }).when(restoredUploadStorage).deleteQuarantineObject(anyString());
        when(deletions.claim(eq(deletionId), any(), any(), any())).thenReturn(Optional.of(job));
        when(deletions.findNextPendingObject(deletionId)).thenAnswer(invocation ->
                pending.isEmpty() ? Optional.empty() : Optional.of(pending.getFirst()));
        AtomicBoolean firstManifestAckFails = new AtomicBoolean(true);
        doAnswer(invocation -> {
            String deletedKey = invocation.getArgument(1);
            if (firstManifestAckFails.getAndSet(false)) {
                throw new IllegalStateException("simulated manifest acknowledgement failure after S3 delete");
            }
            pending.removeIf(object -> object.objectKey().equals(deletedKey));
            return null;
        }).when(deletions).markObjectDeleted(eq(job), anyString(), any());

        AccountDeletionQueueProperties queueProperties = new AccountDeletionQueueProperties("", 1, 1,
                Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofSeconds(90));
        AccountDeletionWorker worker = new AccountDeletionWorker(deletions,
                mock(AccountDeletionWorkflowService.class), mock(CognitoAccountAdministrationPort.class),
                trustedStorage, restoredUploadStorage, queueProperties, clock);

        assertThatThrownBy(() -> worker.process(deletionId)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manifest acknowledgement failure");
        worker.process(deletionId); // Repeats an S3 delete whose DB acknowledgement failed.
        worker.process(deletionId);
        worker.process(deletionId);
        worker.process(deletionId);

        assertThat(trustedDeleteAttempts).containsExactly(documentKey, documentKey, uploadTrustedKey);
        assertThat(quarantineDeleteAttempts).containsExactly(quarantineKey);
        assertThat(trustedObjects).isEmpty();
        assertThat(quarantineObjects).isEmpty();
        assertThat(pending).isEmpty();
        verify(deletions).advance(eq(job), eq(AccountDeletionPhase.DELETE_COGNITO), any(), any());
    }

    @Test
    void existingIncompleteDeletionAlsoRefreshesItsManifestBeforeWorkersResume() {
        UUID deletionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AccountDeletionRepository deletions = mock(AccountDeletionRepository.class);
        UserRepository users = mock(UserRepository.class);
        UploadObjectStoragePort uploadStorage = mock(UploadObjectStoragePort.class);
        AccountDeletionObjectManifest objectManifest = new AccountDeletionObjectManifest(deletions, uploadStorage);
        AccountDeletionJob existingJob = new AccountDeletionJob(deletionId, userId,
                AccountDeletionPhase.DELETE_OBJECTS, NOW, NOW, NOW, UUID.randomUUID());
        String restoredDocumentKey = "private/trusted/documents/restored-incomplete-document";
        AccountDeletionTombstone tombstone = new AccountDeletionTombstone(deletionId, userId, "source-resource",
                List.of("v1_fingerprint"), AccountDeletionTombstone.State.IN_PROGRESS,
                NOW.minusMinutes(10), null, null, null, 0);
        when(deletions.find(deletionId)).thenReturn(Optional.of(existingJob));
        when(deletions.findOwnedTrustedObjectKeys(userId)).thenReturn(List.of(restoredDocumentKey));
        when(deletions.findOwnedUploads(userId)).thenReturn(List.of());

        RestoreReconciliationBootstrap bootstrap = new RestoreReconciliationBootstrap(deletions, users,
                new AccountDeletionProperties(Duration.ofMinutes(70), Duration.ZERO), objectManifest,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        assertThat(bootstrap.ensureJob(tombstone)).contains(deletionId);

        verify(deletions).captureObjects(deletionId, List.of(new AccountDeletionRepository.PendingObject(
                restoredDocumentKey, AccountDeletionObjectKind.TRUSTED)));
    }
}
