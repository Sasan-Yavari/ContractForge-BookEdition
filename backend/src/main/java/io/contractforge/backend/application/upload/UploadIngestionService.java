package io.contractforge.backend.application.upload;

import io.contractforge.backend.application.port.MalwareScannerPort;
import io.contractforge.backend.application.port.ActiveAccountWritePort;
import io.contractforge.backend.application.port.ProcessingPublisherPort;
import io.contractforge.backend.application.port.MalwareScannerPort.ScanResult;
import io.contractforge.backend.application.port.RepositoryPorts.UploadRepository;
import io.contractforge.backend.application.port.UploadObjectStoragePort;
import io.contractforge.backend.application.port.UploadObjectStoragePort.ObjectMetadata;
import io.contractforge.backend.application.accountdeletion.AccountDeletionInProgressException;
import io.contractforge.backend.domain.model.PersistenceModels.Upload;
import io.contractforge.backend.domain.model.UploadStatus;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/** Processes one S3 ObjectCreated command at the application boundary. */
@Service
@Profile("!test")
public class UploadIngestionService {
    private static final int PDF_SIGNATURE_LENGTH = 5;
    private static final String PDF_SIGNATURE = "%PDF-";
    private final UploadRepository uploads;
    private final UploadObjectStoragePort storage;
    private final MalwareScannerPort malwareScanner;
    private final UploadCheckpointService checkpoints;
    private final UploadProperties properties;
    private final UploadFinalizationService finalization;
    private final ProcessingPublisherPort processingPublisher;
    private final ActiveAccountWritePort activeAccounts;

    public UploadIngestionService(UploadRepository uploads, UploadObjectStoragePort storage,
                                  MalwareScannerPort malwareScanner, UploadCheckpointService checkpoints,
                                  UploadProperties properties) {
        this(uploads, storage, malwareScanner, checkpoints, properties, null, null);
    }

    public UploadIngestionService(UploadRepository uploads, UploadObjectStoragePort storage,
                                  MalwareScannerPort malwareScanner, UploadCheckpointService checkpoints,
                                  UploadProperties properties, UploadFinalizationService finalization,
                                  ProcessingPublisherPort processingPublisher) {
        this(uploads, storage, malwareScanner, checkpoints, properties, finalization, processingPublisher, null);
    }

    @Autowired
    public UploadIngestionService(UploadRepository uploads, UploadObjectStoragePort storage,
                                  MalwareScannerPort malwareScanner, UploadCheckpointService checkpoints,
                                  UploadProperties properties, UploadFinalizationService finalization,
                                  ProcessingPublisherPort processingPublisher, ActiveAccountWritePort activeAccounts) {
        this.uploads = uploads;
        this.storage = storage;
        this.malwareScanner = malwareScanner;
        this.checkpoints = checkpoints;
        this.properties = properties;
        this.finalization = finalization;
        this.processingPublisher = processingPublisher;
        this.activeAccounts = activeAccounts;
    }

    public Upload process(ObjectCreatedCommand command) {
        if (command == null || command.uploadId() == null || command.objectKey() == null
                || !isExpectedKey(command.objectKey(), command.uploadId())) {
            throw new UploadRejectedException();
        }
        Upload upload = uploads.findById(command.uploadId()).orElse(null);
        if (upload == null) {
            cleanupLateEvent(command.uploadId(), command.objectKey());
            throw new UploadNotFoundException();
        }
        if (activeAccounts != null && !activeAccounts.lockActiveUser(upload.userId())) {
            cleanupLateEvent(command.uploadId(), command.objectKey());
            throw new UploadRejectedException();
        }
        if (!command.objectKey().equals(upload.objectKey())) {
            throw new UploadRejectedException();
        }
        if (UploadStatus.SCANNED.equals(upload.status())) {
            return finish(upload);
        }
        if ((UploadStatus.PROMOTED.equals(upload.status()) || UploadStatus.COMPLETED.equals(upload.status()))
                && finalization != null) {
            return finish(upload);
        }
        if (UploadStatus.PROMOTED.equals(upload.status()) || UploadStatus.COMPLETED.equals(upload.status())) {
            throw new UploadRejectedException();
        }
        if (UploadStatus.REJECTED.equals(upload.status())) {
            storage.deleteQuarantineObject(command.objectKey());
            return upload;
        }
        if (!UploadStatus.UPLOADING.equals(upload.status()) && !UploadStatus.UPLOADED.equals(upload.status())) {
            throw new UploadRejectedException();
        }

        try {
            if (UploadStatus.UPLOADING.equals(upload.status())) {
                ObjectMetadata metadata = storage.head(command.objectKey());
                validateObject(command.uploadId(), command.objectKey(), metadata);
                validateContent(command.uploadId(), command.objectKey(), metadata);
                upload = checkpoints.advanceToUploaded(command.uploadId(), command.objectKey());
            }

            ScanResult scanResult = malwareScanner.scan(command.objectKey());
            return switch (scanResult) {
                case CLEAN -> finish(checkpoints.advanceToScanned(command.uploadId()));
                case MALICIOUS -> {
                    throw rejectAndCleanup(command.uploadId(), command.objectKey());
                }
                case PENDING, TRANSIENT_ERROR -> throw new RetryableIngestionException();
            };
        } catch (AccountDeletionInProgressException | UploadNotFoundException deletedDuringProcessing) {
            cleanupLateEvent(command.uploadId(), command.objectKey());
            throw new UploadRejectedException();
        }
    }

    private Upload finish(Upload upload) {
        if (finalization == null || processingPublisher == null) {
            return upload;
        }
        Upload completed = finalization.finalizeUpload(upload.id(), upload.objectKey());
        processingPublisher.publish(new ProcessingPublisherPort.ProcessingMessage(completed.id()));
        storage.deleteQuarantineObject(completed.objectKey());
        return completed;
    }

    private void validateObject(UUID uploadId, String expectedKey, ObjectMetadata metadata) {
        if (metadata == null || !expectedKey.equals(metadata.objectKey()) || metadata.contentLength() <= 0
                || metadata.contentLength() > properties.maxSizeBytes()
                || metadata.contentType() == null
                || !properties.supportedContentTypes().contains(metadata.contentType().toLowerCase(Locale.ROOT))) {
            throw rejectAndCleanup(uploadId, expectedKey);
        }
    }

    private void validateContent(UUID uploadId, String objectKey, ObjectMetadata metadata) {
        byte[] prefix = storage.readPrefix(objectKey, PDF_SIGNATURE_LENGTH);
        if (prefix == null || prefix.length == 0 || isPdfWithInvalidSignature(metadata, prefix)) {
            throw rejectAndCleanup(uploadId, objectKey);
        }
    }

    private boolean isPdfWithInvalidSignature(ObjectMetadata metadata, byte[] prefix) {
        return "application/pdf".equalsIgnoreCase(metadata.contentType())
                && !new String(prefix, StandardCharsets.US_ASCII).startsWith(PDF_SIGNATURE);
    }

    private UploadIngestionException rejectAndCleanup(UUID uploadId, String objectKey) {
        checkpoints.advanceToRejected(uploadId, objectKey);
        storage.deleteQuarantineObject(objectKey);
        return new UploadRejectedException();
    }

    private boolean isExpectedKey(String key, UUID uploadId) {
        return key.startsWith(properties.objectKeyPrefix()) && key.equals(properties.objectKeyPrefix() + uploadId)
                && key.startsWith(UploadProperties.QUARANTINE_OBJECT_KEY_PREFIX) && !key.contains("..");
    }

    private void cleanupLateEvent(UUID uploadId, String objectKey) {
        storage.deleteQuarantineObject(objectKey);
        storage.deleteTrustedObjectFor(uploadId);
    }

    public record ObjectCreatedCommand(UUID uploadId, String objectKey) { }

    public static class UploadIngestionException extends RuntimeException { }
    public static class UploadNotFoundException extends UploadIngestionException { }
    public static class UploadRejectedException extends UploadIngestionException { }
    public static class RetryableIngestionException extends UploadIngestionException { }
}
