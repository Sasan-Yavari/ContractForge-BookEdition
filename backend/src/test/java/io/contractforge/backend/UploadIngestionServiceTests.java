package io.contractforge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.contractforge.backend.application.port.ActiveAccountWritePort;
import io.contractforge.backend.application.port.MalwareScannerPort;
import io.contractforge.backend.application.port.ProcessingPublisherPort;
import io.contractforge.backend.application.port.RepositoryPorts;
import io.contractforge.backend.application.port.UploadObjectStoragePort;
import io.contractforge.backend.application.port.UploadObjectStoragePort.ObjectMetadata;
import io.contractforge.backend.application.upload.UploadCheckpointService;
import io.contractforge.backend.application.upload.UploadFinalizationService;
import io.contractforge.backend.application.upload.UploadIngestionService;
import io.contractforge.backend.application.upload.UploadProperties;
import io.contractforge.backend.domain.model.PersistenceModels.Upload;
import io.contractforge.backend.domain.model.UploadStatus;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;

class UploadIngestionServiceTests {
    private static final UUID UPLOAD_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final String KEY = "quarantine/incoming/" + UPLOAD_ID;

    @Test
    void springSelectsOneProductionConstructorThatIncludesTheAccountDeletionFence() throws NoSuchMethodException {
        var constructors = new AutowiredAnnotationBeanPostProcessor()
                .determineCandidateConstructors(UploadIngestionService.class, "uploadIngestionService");
        var productionConstructor = UploadIngestionService.class.getConstructor(
                RepositoryPorts.UploadRepository.class, UploadObjectStoragePort.class, MalwareScannerPort.class,
                UploadCheckpointService.class, UploadProperties.class, UploadFinalizationService.class,
                ProcessingPublisherPort.class, ActiveAccountWritePort.class);

        assertThat(constructors).containsExactly(productionConstructor);
    }

    @Test
    void verifiesActualObjectAndCleanScanBeforeAdvancingBothCheckpoints() {
        Fixture fixture = new Fixture(MalwareScannerPort.ScanResult.CLEAN);

        Upload result = fixture.service.process(new UploadIngestionService.ObjectCreatedCommand(UPLOAD_ID, KEY));

        assertThat(result.status()).isEqualTo(UploadStatus.SCANNED);
        assertThat(fixture.storage.headCalls).isEqualTo(1);
        assertThat(fixture.storage.prefixCalls).isEqualTo(1);
        assertThat(fixture.scanner.calls).isEqualTo(1);
        assertThat(fixture.repository.upload().status()).isEqualTo(UploadStatus.SCANNED);
    }

    @Test
    void duplicateDeliveryIsIdempotentAndAlreadyScannedSkipsExternalChecks() {
        Fixture fixture = new Fixture(MalwareScannerPort.ScanResult.CLEAN);
        UploadIngestionService.ObjectCreatedCommand command = new UploadIngestionService.ObjectCreatedCommand(UPLOAD_ID, KEY);

        fixture.service.process(command);
        fixture.service.process(command);

        assertThat(fixture.repository.upload().status()).isEqualTo(UploadStatus.SCANNED);
        assertThat(fixture.storage.headCalls).isEqualTo(1);
        assertThat(fixture.scanner.calls).isEqualTo(1);
    }

    @Test
    void eventKeyCannotHijackAnotherUploadAndClientClaimsAreNotAccepted() {
        Fixture fixture = new Fixture(MalwareScannerPort.ScanResult.CLEAN);

        assertThatThrownBy(() -> fixture.service.process(new UploadIngestionService.ObjectCreatedCommand(
                UPLOAD_ID, "quarantine/incoming/80000000-0000-0000-0000-000000000001")))
                .isInstanceOf(UploadIngestionService.UploadRejectedException.class);
        assertThat(fixture.repository.upload().status()).isEqualTo(UploadStatus.UPLOADING);
        assertThat(fixture.scanner.calls).isZero();
    }

    @Test
    void invalidObjectIsCleanedUpAndNeverScanned() {
        Fixture fixture = new Fixture(MalwareScannerPort.ScanResult.CLEAN);
        fixture.storage.metadata = new ObjectMetadata(KEY, 101, "application/pdf", new HashMap<>());

        assertThatThrownBy(() -> fixture.service.process(new UploadIngestionService.ObjectCreatedCommand(UPLOAD_ID, KEY)))
                .isInstanceOf(UploadIngestionService.UploadRejectedException.class);
        assertThat(fixture.storage.deletedKeys).containsExactly(KEY);
        assertThat(fixture.scanner.calls).isZero();
        assertThat(fixture.repository.upload().status()).isEqualTo(UploadStatus.REJECTED);
    }

    @Test
    void invalidPdfMagicBytesAreRejectedEvenWhenMimeTypeClaimsPdf() {
        Fixture fixture = new Fixture(MalwareScannerPort.ScanResult.CLEAN);
        fixture.storage.prefix = "not a pdf".getBytes();

        assertThatThrownBy(() -> fixture.service.process(new UploadIngestionService.ObjectCreatedCommand(UPLOAD_ID, KEY)))
                .isInstanceOf(UploadIngestionService.UploadRejectedException.class);
        assertThat(fixture.storage.deletedKeys).containsExactly(KEY);
        assertThat(fixture.repository.upload().status()).isEqualTo(UploadStatus.REJECTED);
    }

    @Test
    void unsupportedContentTypeIsRejectedBeforeScanning() {
        Fixture fixture = new Fixture(MalwareScannerPort.ScanResult.CLEAN);
        fixture.storage.metadata = new ObjectMetadata(KEY, 42, "image/jpeg", new HashMap<>());

        assertThatThrownBy(() -> fixture.service.process(new UploadIngestionService.ObjectCreatedCommand(UPLOAD_ID, KEY)))
                .isInstanceOf(UploadIngestionService.UploadRejectedException.class);
        assertThat(fixture.repository.upload().status()).isEqualTo(UploadStatus.REJECTED);
        assertThat(fixture.storage.deletedKeys).containsExactly(KEY);
        assertThat(fixture.scanner.calls).isZero();
    }

    @Test
    void acceptsObjectContentTypeWhenConfiguredWithDifferentCase() {
        Fixture fixture = new Fixture(MalwareScannerPort.ScanResult.CLEAN, List.of("APPLICATION/PDF"));

        Upload result = fixture.service.process(new UploadIngestionService.ObjectCreatedCommand(UPLOAD_ID, KEY));

        assertThat(result.status()).isEqualTo(UploadStatus.SCANNED);
        assertThat(fixture.scanner.calls).isEqualTo(1);
    }

    @Test
    void objectMetadataKeyMismatchIsRejectedBeforeScanning() {
        Fixture fixture = new Fixture(MalwareScannerPort.ScanResult.CLEAN);
        fixture.storage.metadata = new ObjectMetadata("quarantine/incoming/other", 42, "application/pdf", new HashMap<>());

        assertThatThrownBy(() -> fixture.service.process(new UploadIngestionService.ObjectCreatedCommand(UPLOAD_ID, KEY)))
                .isInstanceOf(UploadIngestionService.UploadRejectedException.class);
        assertThat(fixture.repository.upload().status()).isEqualTo(UploadStatus.REJECTED);
        assertThat(fixture.storage.deletedKeys).containsExactly(KEY);
        assertThat(fixture.scanner.calls).isZero();
    }

    @Test
    void pendingScanIsRetryableFromUploadedCheckpoint() {
        Fixture fixture = new Fixture(MalwareScannerPort.ScanResult.PENDING);

        assertThatThrownBy(() -> fixture.service.process(new UploadIngestionService.ObjectCreatedCommand(UPLOAD_ID, KEY)))
                .isInstanceOf(UploadIngestionService.RetryableIngestionException.class);
        assertThat(fixture.repository.upload().status()).isEqualTo(UploadStatus.UPLOADED);
        assertThat(fixture.storage.deletedKeys).isEmpty();
    }

    @Test
    void maliciousScanIsRejectedWithoutCreatingDownstreamRecords() {
        Fixture fixture = new Fixture(MalwareScannerPort.ScanResult.MALICIOUS);
        UploadIngestionService.ObjectCreatedCommand command =
                new UploadIngestionService.ObjectCreatedCommand(UPLOAD_ID, KEY);

        assertThatThrownBy(() -> fixture.service.process(command))
                .isInstanceOf(UploadIngestionService.UploadRejectedException.class);
        assertThat(fixture.repository.upload().status()).isEqualTo(UploadStatus.REJECTED);
        assertThat(fixture.storage.deletedKeys).containsExactly(KEY);

        Upload result = fixture.service.process(command);
        assertThat(result.status()).isEqualTo(UploadStatus.REJECTED);
        assertThat(fixture.storage.deletedKeys).containsExactly(KEY, KEY);
        assertThat(fixture.scanner.calls).isEqualTo(1);
    }

    @Test
    void cleanupFailureLeavesRejectedUploadTerminalForSafeRedelivery() {
        Fixture fixture = new Fixture(MalwareScannerPort.ScanResult.MALICIOUS);
        fixture.storage.failDelete = true;

        assertThatThrownBy(() -> fixture.service.process(new UploadIngestionService.ObjectCreatedCommand(UPLOAD_ID, KEY)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fixture.repository.upload().status()).isEqualTo(UploadStatus.REJECTED);
    }

    @Test
    void lateObjectCreatedAfterAccountPurgeDeletesBothGeneratedObjectNamespaces() {
        RepositoryPorts.UploadRepository missingUpload = mock(RepositoryPorts.UploadRepository.class);
        UploadObjectStoragePort storage = mock(UploadObjectStoragePort.class);
        when(missingUpload.findById(UPLOAD_ID)).thenReturn(Optional.empty());
        UploadProperties properties = new UploadProperties(100, Duration.ofMinutes(15), List.of("application/pdf"),
                "quarantine/incoming/");
        UploadIngestionService service = new UploadIngestionService(missingUpload, storage,
                new FakeScanner(MalwareScannerPort.ScanResult.CLEAN), new UploadCheckpointService(missingUpload), properties);

        assertThatThrownBy(() -> service.process(new UploadIngestionService.ObjectCreatedCommand(UPLOAD_ID, KEY)))
                .isInstanceOf(UploadIngestionService.UploadNotFoundException.class);

        verify(storage).deleteQuarantineObject(KEY);
        verify(storage).deleteTrustedObjectFor(UPLOAD_ID);
    }

    @Test
    void illegalCheckpointCannotSkipMalwareGate() {
        Fixture fixture = new Fixture(MalwareScannerPort.ScanResult.CLEAN);
        fixture.repository.status = UploadStatus.PROMOTED;

        assertThatThrownBy(() -> fixture.service.process(new UploadIngestionService.ObjectCreatedCommand(UPLOAD_ID, KEY)))
                .isInstanceOf(UploadIngestionService.UploadRejectedException.class);
        assertThat(fixture.scanner.calls).isZero();
    }

    private static final class Fixture {
        private final InMemoryUploadRepository repository = new InMemoryUploadRepository();
        private final FakeObjectStorage storage = new FakeObjectStorage();
        private final FakeScanner scanner;
        private final UploadIngestionService service;

        private Fixture(MalwareScannerPort.ScanResult result) {
            this(result, List.of("application/pdf"));
        }

        private Fixture(MalwareScannerPort.ScanResult result, List<String> supportedContentTypes) {
            scanner = new FakeScanner(result);
            UploadProperties properties = new UploadProperties(100, Duration.ofMinutes(15), supportedContentTypes,
                    "quarantine/incoming/");
            service = new UploadIngestionService(repository, storage, scanner,
                    new UploadCheckpointService(repository), properties);
        }
    }

    private static final class InMemoryUploadRepository implements RepositoryPorts.UploadRepository {
        private UploadStatus status = UploadStatus.UPLOADING;

        private Upload upload() {
            return new Upload(UPLOAD_ID, UUID.randomUUID(), status, KEY, "contract.pdf",
                    OffsetDateTime.now().plusMinutes(15), OffsetDateTime.now());
        }

        @Override public void save(Upload upload) { status = upload.status(); }
        @Override public Optional<Upload> findById(UUID id) {
            return UPLOAD_ID.equals(id) ? Optional.of(upload()) : Optional.empty();
        }
        @Override public int markUploaded(UUID id, String expectedObjectKey) {
            if (UploadStatus.UPLOADING.equals(status) && KEY.equals(expectedObjectKey)) { status = UploadStatus.UPLOADED; return 1; }
            return 0;
        }
        @Override public int markScanned(UUID id) {
            if (UploadStatus.UPLOADED.equals(status)) { status = UploadStatus.SCANNED; return 1; }
            return 0;
        }
        @Override public int markPromoted(UUID id) { return 0; }
        @Override public int markCompleted(UUID id) { return 0; }
        @Override public Optional<Upload> findByIdForUpdate(UUID id) { return findById(id); }
        @Override public int markRejected(UUID id, String expectedObjectKey) {
            if ((UploadStatus.UPLOADING.equals(status) || UploadStatus.UPLOADED.equals(status))
                    && KEY.equals(expectedObjectKey)) { status = UploadStatus.REJECTED; return 1; }
            return 0;
        }
    }

    private static final class FakeObjectStorage implements UploadObjectStoragePort {
        private ObjectMetadata metadata = new ObjectMetadata(KEY, 42, "application/pdf", new HashMap<>());
        private byte[] prefix = "%PDF-1.7".getBytes();
        private int headCalls;
        private int prefixCalls;
        private final List<String> deletedKeys = new java.util.ArrayList<>();
        private boolean failDelete;

        @Override public ObjectMetadata head(String objectKey) { headCalls++; return metadata; }
        @Override public String trustedKeyFor(UUID uploadId) { return "private/trusted/documents/" + uploadId; }
        @Override public void promoteToTrusted(String sourceKey, String trustedKey) { }
        @Override public ObjectMetadata headTrusted(String objectKey) { return metadata; }
        @Override public byte[] readPrefix(String objectKey, int maximumBytes) { prefixCalls++; return prefix; }
        @Override public void deleteQuarantineObject(String objectKey) {
            deletedKeys.add(objectKey);
            if (failDelete) { throw new IllegalStateException("cleanup failed"); }
        }
    }

    private static final class FakeScanner implements MalwareScannerPort {
        private final ScanResult result;
        private int calls;
        private FakeScanner(ScanResult result) { this.result = result; }
        @Override public ScanResult scan(String objectKey) { calls++; return result; }
    }
}
