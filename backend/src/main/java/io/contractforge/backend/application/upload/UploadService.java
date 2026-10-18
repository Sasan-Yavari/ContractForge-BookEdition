package io.contractforge.backend.application.upload;

import io.contractforge.backend.application.port.ObjectStoragePort;
import io.contractforge.backend.application.port.ObjectStoragePort.UploadInstructions;
import io.contractforge.backend.application.port.ActiveAccountWritePort;
import io.contractforge.backend.application.port.RepositoryPorts.UploadRepository;
import io.contractforge.backend.application.accountdeletion.AccountDeletionInProgressException;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import io.contractforge.backend.domain.model.PersistenceModels.Upload;
import io.contractforge.backend.domain.model.ContractType;
import io.contractforge.backend.domain.model.UploadStatus;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

@Service
@Profile("!test")
public class UploadService {
    private static final int MAX_FILENAME_LENGTH = 255;
    private static final int FIRST_CONTROL_CHARACTER = 0x20;
    private static final int DELETE_CONTROL_CHARACTER = 0x7f;

    private final UploadRepository uploads;
    private final ObjectStoragePort storage;
    private final UploadProperties properties;
    private final UploadInitiationRateLimiter initiationRateLimiter;
    private final ActiveAccountWritePort activeAccounts;

    public UploadService(UploadRepository uploads, ObjectStoragePort storage, UploadProperties properties,
                         UploadInitiationRateLimiter initiationRateLimiter) {
        this(uploads, storage, properties, initiationRateLimiter, null);
    }

    @Autowired
    public UploadService(UploadRepository uploads, ObjectStoragePort storage, UploadProperties properties,
                         UploadInitiationRateLimiter initiationRateLimiter, ActiveAccountWritePort activeAccounts) {
        this.uploads = uploads;
        this.storage = storage;
        this.properties = properties;
        this.initiationRateLimiter = initiationRateLimiter;
        this.activeAccounts = activeAccounts;
    }

    @Transactional
    public InitiatedUpload initiate(AuthenticatedApplicationUser user, StartUpload request) {
        validate(user, request);
        if (activeAccounts != null && !activeAccounts.lockActiveUser(user.userId())) {
            throw new AccountDeletionInProgressException();
        }
        initiationRateLimiter.acquire(user);
        UUID id = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now();
        OffsetDateTime expiresAt = createdAt.plus(properties.expiry());
        String objectKey = properties.objectKeyPrefix() + id;
        Upload upload = new Upload(id, user.userId(), UploadStatus.UPLOADING,
                objectKey, request.filename(), request.contractType(), expiresAt, createdAt);
        UploadInstructions instructions = storage.presignPut(objectKey, request.contentType(), request.size());
        uploads.save(upload);
        return new InitiatedUpload(upload, instructions);
    }

    @Transactional(readOnly = true)
    public UploadStatusView status(AuthenticatedApplicationUser user, UUID id) {
        if (user == null || id == null) {
            throw new UploadNotFoundException();
        }
        Upload upload = uploads.findById(id)
                .filter(candidate -> user.userId().equals(candidate.userId()))
                .orElseThrow(UploadNotFoundException::new);
        String status = UploadStatus.UPLOADING.equals(upload.status())
                && upload.expiresAt().isBefore(OffsetDateTime.now())
                ? UploadStatus.EXPIRED.value() : upload.status().value();
        return new UploadStatusView(upload.id(), status, upload.contractType(), upload.originalFilename(), upload.expiresAt());
    }

    private void validate(AuthenticatedApplicationUser user, StartUpload request) {
        if (user == null || user.userId() == null || request == null || !validFilename(request.filename())
                || !validContentType(request.contentType()) || request.contractType() == null || request.size() <= 0
                || request.size() > properties.maxSizeBytes()) {
            throw new UploadValidationException();
        }
        if (!validConfiguration()) {
            throw new UploadValidationException();
        }
    }

    private boolean validFilename(String filename) {
        return filename != null && !filename.isBlank() && filename.length() <= MAX_FILENAME_LENGTH
                && !filename.contains("..")
                && filename.chars().noneMatch(character -> character < FIRST_CONTROL_CHARACTER
                || character == DELETE_CONTROL_CHARACTER);
    }

    private boolean validContentType(String contentType) {
        return contentType != null && !contentType.isBlank()
                && properties.supportedContentTypes().contains(contentType.toLowerCase(Locale.ROOT));
    }

    private boolean validConfiguration() {
        return properties.expiry() != null && !properties.expiry().isZero() && !properties.expiry().isNegative()
                && properties.objectKeyPrefix() != null
                && properties.objectKeyPrefix().startsWith(UploadProperties.QUARANTINE_OBJECT_KEY_PREFIX);
    }

    public record StartUpload(String filename, String contentType, long size, ContractType contractType) { }
    public record InitiatedUpload(Upload upload, UploadInstructions instructions) { }
    public record UploadStatusView(UUID id, String status, ContractType contractType,
                                   String originalFilename, OffsetDateTime expiresAt) { }

    public static class UploadValidationException extends RuntimeException { }
    public static class UploadNotFoundException extends RuntimeException { }
}
