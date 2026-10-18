package io.contractforge.backend.application.upload;

import io.contractforge.backend.application.port.RepositoryPorts.DocumentRepository;
import io.contractforge.backend.application.port.RepositoryPorts.UploadRepository;
import io.contractforge.backend.application.port.ActiveAccountWritePort;
import io.contractforge.backend.application.accountdeletion.AccountDeletionInProgressException;
import io.contractforge.backend.application.port.UploadObjectStoragePort;
import io.contractforge.backend.application.port.UploadObjectStoragePort.ObjectMetadata;
import io.contractforge.backend.domain.model.PersistenceModels.Document;
import io.contractforge.backend.domain.model.DocumentStatus;
import io.contractforge.backend.domain.model.PersistenceModels.Upload;
import io.contractforge.backend.domain.model.UploadStatus;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

/** Promotes a scanned upload and atomically creates its Document. */
@Service
@Profile("!test")
public class UploadFinalizationService {
    private final UploadRepository uploads;
    private final DocumentRepository documents;
    private final UploadObjectStoragePort storage;
    private final ActiveAccountWritePort activeAccounts;

    public UploadFinalizationService(UploadRepository uploads, DocumentRepository documents,
                                     UploadObjectStoragePort storage) {
        this(uploads, documents, storage, null);
    }

    @Autowired
    public UploadFinalizationService(UploadRepository uploads, DocumentRepository documents,
                                     UploadObjectStoragePort storage, ActiveAccountWritePort activeAccounts) {
        this.uploads = uploads;
        this.documents = documents;
        this.storage = storage;
        this.activeAccounts = activeAccounts;
    }

    @Transactional
    public Upload finalizeUpload(UUID uploadId, String sourceKey) {
        Upload upload = uploads.findById(uploadId).orElseThrow(UploadIngestionService.UploadNotFoundException::new);
        requireActive(upload);
        if (!sourceKey.equals(upload.objectKey())) {
            throw new UploadIngestionService.UploadRejectedException();
        }
        if (UploadStatus.SCANNED.equals(upload.status())) {
            promote(upload);
        } else if (!UploadStatus.PROMOTED.equals(upload.status()) && !UploadStatus.COMPLETED.equals(upload.status())) {
            throw new UploadIngestionService.UploadRejectedException();
        }
        Upload finalized = complete(uploadId);
        if (UploadStatus.COMPLETED.equals(finalized.status())) {
            return finalized;
        }
        throw new IllegalStateException("Upload finalization did not complete: " + uploadId);
    }

    private void promote(Upload upload) {
        String trustedKey = storage.trustedKeyFor(upload.id());
        ObjectMetadata source = storage.head(upload.objectKey());
        if (source == null || !upload.objectKey().equals(source.objectKey()) || source.contentLength() <= 0) {
            throw new UploadIngestionService.RetryableIngestionException();
        }
        ObjectMetadata trusted;
        try {
            trusted = storage.headTrusted(trustedKey);
        } catch (UploadObjectStoragePort.TrustedObjectNotFoundException missing) {
            storage.promoteToTrusted(upload.objectKey(), trustedKey);
            trusted = storage.headTrusted(trustedKey);
        }
        if (!trustedObjectMatches(trusted, trustedKey, source)) {
            throw new UploadIngestionService.RetryableIngestionException();
        }
        uploads.markPromoted(upload.id());
    }

    private boolean trustedObjectMatches(ObjectMetadata trusted, String trustedKey, ObjectMetadata source) {
        return trusted != null && trustedKey.equals(trusted.objectKey())
                && trusted.contentLength() == source.contentLength()
                && trusted.contentType().equalsIgnoreCase(source.contentType());
    }

    Upload complete(UUID uploadId) {
        Upload upload = uploads.findByIdForUpdate(uploadId)
                .orElseThrow(UploadIngestionService.UploadNotFoundException::new);
        requireActive(upload);
        Document existing = documents.findByIdForUpdate(uploadId).orElse(null);
        if (UploadStatus.COMPLETED.equals(upload.status())) {
            if (existing == null || !matches(existing, upload)) {
                throw new IllegalStateException("Inconsistent completed upload: " + uploadId);
            }
            return upload;
        }
        if (!UploadStatus.PROMOTED.equals(upload.status()) || existing != null) {
            throw new IllegalStateException("Inconsistent promoted upload: " + uploadId);
        }
        ObjectMetadata trusted = storage.headTrusted(storage.trustedKeyFor(upload.id()));
        Document document = new Document(upload.id(), upload.userId(), trusted.objectKey(), upload.originalFilename(),
                trusted.contentType(), trusted.contentLength(), upload.contractType(), DocumentStatus.PROCESSING,
                OffsetDateTime.now(), OffsetDateTime.now());
        documents.save(document);
        if (uploads.markCompleted(upload.id()) != 1) {
            throw new IllegalStateException("Upload completion update failed: " + uploadId);
        }
        return new Upload(upload.id(), upload.userId(), UploadStatus.COMPLETED, upload.objectKey(),
                upload.originalFilename(), upload.contractType(), upload.expiresAt(), upload.createdAt());
    }

    private boolean matches(Document document, Upload upload) {
        return document.id().equals(upload.id()) && document.userId().equals(upload.userId())
                && document.storageKey().equals(storage.trustedKeyFor(upload.id()))
                && document.filename().equals(upload.originalFilename())
                && document.contractType() == upload.contractType();
    }

    private void requireActive(Upload upload) {
        if (activeAccounts != null && !activeAccounts.lockActiveUser(upload.userId())) {
            throw new AccountDeletionInProgressException();
        }
    }
}
