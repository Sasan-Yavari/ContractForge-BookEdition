package io.contractforge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.contractforge.backend.application.port.ObjectStoragePort;
import io.contractforge.backend.application.port.RepositoryPorts;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import io.contractforge.backend.application.upload.UploadInitiationRateLimiter;
import io.contractforge.backend.application.upload.UploadInitiationRateLimitExceededException;
import io.contractforge.backend.application.upload.UploadProperties;
import io.contractforge.backend.application.upload.UploadRateLimitProperties;
import io.contractforge.backend.application.upload.UploadService;
import io.contractforge.backend.domain.model.PersistenceModels.Upload;
import io.contractforge.backend.domain.model.ContractType;
import io.contractforge.backend.domain.model.UploadStatus;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UploadServiceTests {
    private static final UUID OWNER = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Test
    void initiationUsesAuthenticatedOwnerAndServerGeneratedQuarantineKey() {
        InMemoryUploads repository = new InMemoryUploads();
        RecordingStorage storage = new RecordingStorage();
        UploadService service = service(repository, storage, properties());

        UploadService.InitiatedUpload result = service.initiate(new AuthenticatedApplicationUser(OWNER),
                new UploadService.StartUpload("قرارداد برق.pdf", "application/pdf", 42,
                        ContractType.ELECTRICITY));

        assertThat(result.upload().userId()).isEqualTo(OWNER);
        assertThat(result.upload().id()).isNotNull();
        assertThat(result.upload().originalFilename()).isEqualTo("قرارداد برق.pdf");
        assertThat(result.upload().contractType()).isEqualTo(io.contractforge.backend.domain.model.ContractType.ELECTRICITY);
        assertThat(result.upload().objectKey()).matches("quarantine/incoming/[0-9a-f-]{36}");
        assertThat(storage.objectKey).isEqualTo(result.upload().objectKey());
        assertThat(result.upload().expiresAt()).isAfter(OffsetDateTime.now());
        assertThat(storage.instructions.url()).isNotNull();
    }

    @Test
    void invalidRequestsDoNotCreateUploadsOrStorageCapability() {
        InMemoryUploads repository = new InMemoryUploads();
        RecordingStorage storage = new RecordingStorage();
        UploadService service = service(repository, storage, properties());

        assertThatThrownBy(() -> service.initiate(new AuthenticatedApplicationUser(OWNER),
                new UploadService.StartUpload("bad\nname.pdf", "application/pdf", 42,
                        ContractType.ELECTRICITY)))
                .isInstanceOf(UploadService.UploadValidationException.class);
        assertThatThrownBy(() -> service.initiate(new AuthenticatedApplicationUser(OWNER),
                new UploadService.StartUpload("contract.pdf", "application/pdf", 101,
                        ContractType.ELECTRICITY)))
                .isInstanceOf(UploadService.UploadValidationException.class);
        assertThatThrownBy(() -> service.initiate(new AuthenticatedApplicationUser(OWNER),
                new UploadService.StartUpload("contract.pdf", "image/jpeg", 42,
                        ContractType.ELECTRICITY)))
                .isInstanceOf(UploadService.UploadValidationException.class);
        assertThatThrownBy(() -> service.initiate(new AuthenticatedApplicationUser(OWNER),
                new UploadService.StartUpload("contract.pdf", "application/pdf", 42, null)))
                .isInstanceOf(UploadService.UploadValidationException.class);
        assertThat(repository.saved).isNull();
        assertThat(storage.objectKey).isNull();
    }

    @Test
    void acceptsContentTypeWhenConfiguredWithDifferentCase() {
        InMemoryUploads repository = new InMemoryUploads();
        RecordingStorage storage = new RecordingStorage();
        UploadProperties properties = new UploadProperties(100, java.time.Duration.ofMinutes(15),
                java.util.List.of("APPLICATION/PDF"), "quarantine/incoming/");

        service(repository, storage, properties).initiate(new AuthenticatedApplicationUser(OWNER),
                new UploadService.StartUpload("contract.pdf", "application/pdf", 42,
                        ContractType.ELECTRICITY));

        assertThat(repository.saved).isNotNull();
        assertThat(storage.objectKey).isEqualTo(repository.saved.objectKey());
    }

    @Test
    void rejectedInitiationDoesNotPresignOrPersistAnotherUpload() {
        InMemoryUploads repository = new InMemoryUploads();
        RecordingStorage storage = new RecordingStorage();
        UploadInitiationRateLimiter limiter = new UploadInitiationRateLimiter(
                new UploadRateLimitProperties(1, java.time.Duration.ofMinutes(1), 10));
        UploadService service = new UploadService(repository, storage, properties(), limiter);
        UploadService.StartUpload request = new UploadService.StartUpload("contract.pdf", "application/pdf", 42,
                ContractType.ELECTRICITY);

        UploadService.InitiatedUpload allowed = service.initiate(new AuthenticatedApplicationUser(OWNER), request);
        String issuedObjectKey = storage.objectKey;

        assertThatThrownBy(() -> service.initiate(new AuthenticatedApplicationUser(OWNER), request))
                .isInstanceOf(UploadInitiationRateLimitExceededException.class);

        assertThat(repository.saved.id()).isEqualTo(allowed.upload().id());
        assertThat(storage.objectKey).isEqualTo(issuedObjectKey);
    }

    @Test
    void statusEnforcesOwnershipAndDerivesExpiredWithoutChangingStoredState() {
        InMemoryUploads repository = new InMemoryUploads();
        OffsetDateTime expired = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        Upload upload = new Upload(UUID.randomUUID(), OWNER, UploadStatus.UPLOADING, "quarantine/incoming/id", "contract.pdf", expired,
                expired.minusMinutes(1));
        repository.saved = upload;
        UploadService service = service(repository, new RecordingStorage(), properties());

        assertThat(service.status(new AuthenticatedApplicationUser(OWNER), upload.id()).status()).isEqualTo("EXPIRED");
        assertThat(repository.saved.status()).isEqualTo(UploadStatus.UPLOADING);
        assertThatThrownBy(() -> service.status(new AuthenticatedApplicationUser(OTHER), upload.id()))
                .isInstanceOf(UploadService.UploadNotFoundException.class);
    }

    @Test
    void persistedNonUploadingStatusIsReturnedUnchanged() {
        InMemoryUploads repository = new InMemoryUploads();
        Upload upload = new Upload(UUID.randomUUID(), OWNER, UploadStatus.COMPLETED, "quarantine/incoming/id", "contract.pdf",
                OffsetDateTime.now().minusMinutes(1), OffsetDateTime.now().minusMinutes(2));
        repository.saved = upload;

        assertThat(service(repository, new RecordingStorage(), properties())
                .status(new AuthenticatedApplicationUser(OWNER), upload.id()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void persistedRejectedStatusIsReturnedUnchanged() {
        InMemoryUploads repository = new InMemoryUploads();
        Upload upload = new Upload(UUID.randomUUID(), OWNER, UploadStatus.REJECTED, "quarantine/incoming/id", "contract.pdf",
                OffsetDateTime.now().plusMinutes(1), OffsetDateTime.now().minusMinutes(1));
        repository.saved = upload;

        assertThat(service(repository, new RecordingStorage(), properties())
                .status(new AuthenticatedApplicationUser(OWNER), upload.id()).status()).isEqualTo("REJECTED");
    }

    private UploadProperties properties() {
        return new UploadProperties(100, java.time.Duration.ofMinutes(15), java.util.List.of("application/pdf"),
                "quarantine/incoming/");
    }

    private UploadService service(RepositoryPorts.UploadRepository repository, ObjectStoragePort storage,
                                  UploadProperties uploadProperties) {
        UploadRateLimitProperties rateLimitProperties = new UploadRateLimitProperties(100,
                java.time.Duration.ofMinutes(1), 100);
        return new UploadService(repository, storage, uploadProperties,
                new UploadInitiationRateLimiter(rateLimitProperties));
    }

    private static final class InMemoryUploads implements RepositoryPorts.UploadRepository {
        private Upload saved;

        @Override public void save(Upload upload) { saved = upload; }
        @Override public int markUploaded(UUID id, String expectedObjectKey) { return 0; }
        @Override public int markScanned(UUID id) { return 0; }
        @Override public int markPromoted(UUID id) { return 0; }
        @Override public int markCompleted(UUID id) { return 0; }
        @Override public Optional<Upload> findByIdForUpdate(UUID id) { return findById(id); }
        @Override public int markRejected(UUID id, String expectedObjectKey) { return 0; }
        @Override public Optional<Upload> findById(UUID id) {
            return saved != null && saved.id().equals(id) ? Optional.of(saved) : Optional.empty();
        }
    }

    private static final class RecordingStorage implements ObjectStoragePort {
        private String objectKey;
        private ObjectStoragePort.UploadInstructions instructions;

        @Override public ObjectStoragePort.UploadInstructions presignPut(String objectKey, String contentType, long contentLength) {
            this.objectKey = objectKey;
            this.instructions = new ObjectStoragePort.UploadInstructions(URI.create("https://s3.example.test/upload"),
                    new HashMap<>(Map.of("Content-Type", contentType)));
            return instructions;
        }
    }
}
