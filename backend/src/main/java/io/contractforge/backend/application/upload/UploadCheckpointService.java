package io.contractforge.backend.application.upload;

import io.contractforge.backend.application.port.RepositoryPorts.UploadRepository;
import io.contractforge.backend.application.port.ActiveAccountWritePort;
import io.contractforge.backend.application.accountdeletion.AccountDeletionInProgressException;
import io.contractforge.backend.domain.model.PersistenceModels.Upload;
import io.contractforge.backend.domain.model.UploadStatus;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

/** Owns the small, forward-only durable Upload transitions. */
@Service
@Profile("!test")
public class UploadCheckpointService {
    private final UploadRepository uploads;
    private final ActiveAccountWritePort activeAccounts;

    public UploadCheckpointService(UploadRepository uploads) {
        this(uploads, null);
    }

    @Autowired
    public UploadCheckpointService(UploadRepository uploads, ActiveAccountWritePort activeAccounts) {
        this.uploads = uploads;
        this.activeAccounts = activeAccounts;
    }

    @Transactional
    public Upload advanceToUploaded(UUID uploadId, String expectedObjectKey) {
        Upload upload = uploads.findById(uploadId).orElseThrow(UploadIngestionService.UploadNotFoundException::new);
        requireActive(upload);
        if (!expectedObjectKey.equals(upload.objectKey())) {
            throw new UploadIngestionService.UploadRejectedException();
        }
        if (UploadStatus.UPLOADING.equals(upload.status())) {
            uploads.markUploaded(uploadId, expectedObjectKey);
            upload = uploads.findById(uploadId).orElseThrow(UploadIngestionService.UploadNotFoundException::new);
        }
        if (!UploadStatus.UPLOADED.equals(upload.status()) && !UploadStatus.SCANNED.equals(upload.status())) {
            throw new UploadIngestionService.UploadRejectedException();
        }
        return upload;
    }

    @Transactional
    public Upload advanceToScanned(UUID uploadId) {
        Upload upload = uploads.findById(uploadId).orElseThrow(UploadIngestionService.UploadNotFoundException::new);
        requireActive(upload);
        if (UploadStatus.UPLOADED.equals(upload.status())) {
            uploads.markScanned(uploadId);
            upload = uploads.findById(uploadId).orElseThrow(UploadIngestionService.UploadNotFoundException::new);
        }
        if (!UploadStatus.SCANNED.equals(upload.status())) {
            throw new UploadIngestionService.UploadRejectedException();
        }
        return upload;
    }

    @Transactional
    public Upload advanceToRejected(UUID uploadId, String expectedObjectKey) {
        Upload upload = uploads.findById(uploadId).orElseThrow(UploadIngestionService.UploadNotFoundException::new);
        requireActive(upload);
        if (!expectedObjectKey.equals(upload.objectKey())) {
            throw new UploadIngestionService.UploadRejectedException();
        }
        if (UploadStatus.UPLOADING.equals(upload.status()) || UploadStatus.UPLOADED.equals(upload.status())) {
            uploads.markRejected(uploadId, expectedObjectKey);
            upload = uploads.findById(uploadId).orElseThrow(UploadIngestionService.UploadNotFoundException::new);
        }
        if (!UploadStatus.REJECTED.equals(upload.status())) {
            throw new UploadIngestionService.UploadRejectedException();
        }
        return upload;
    }

    @Transactional
    public Upload advanceToPromoted(UUID uploadId) {
        Upload upload = uploads.findById(uploadId).orElseThrow(UploadIngestionService.UploadNotFoundException::new);
        requireActive(upload);
        if (UploadStatus.SCANNED.equals(upload.status())) {
            uploads.markPromoted(uploadId);
            upload = uploads.findById(uploadId).orElseThrow(UploadIngestionService.UploadNotFoundException::new);
        }
        if (!UploadStatus.PROMOTED.equals(upload.status()) && !UploadStatus.COMPLETED.equals(upload.status())) {
            throw new UploadIngestionService.UploadRejectedException();
        }
        return upload;
    }

    private void requireActive(Upload upload) {
        if (activeAccounts != null && !activeAccounts.lockActiveUser(upload.userId())) {
            throw new AccountDeletionInProgressException();
        }
    }
}
