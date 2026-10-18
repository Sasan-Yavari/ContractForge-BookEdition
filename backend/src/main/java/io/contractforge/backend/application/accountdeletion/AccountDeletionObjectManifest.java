package io.contractforge.backend.application.accountdeletion;

import io.contractforge.backend.domain.model.AccountDeletionObjectKind;
import io.contractforge.backend.application.port.UploadObjectStoragePort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Captures every currently owned private object before account rows can be purged. */
@Service
@Profile("!test")
public class AccountDeletionObjectManifest {
    private final AccountDeletionRepository deletions;
    private final UploadObjectStoragePort uploadStorage;

    public AccountDeletionObjectManifest(AccountDeletionRepository deletions,
                                         UploadObjectStoragePort uploadStorage) {
        this.deletions = deletions;
        this.uploadStorage = uploadStorage;
    }

    public void capture(UUID deletionId, UUID userId) {
        List<AccountDeletionRepository.PendingObject> objects = new ArrayList<>();
        deletions.findOwnedTrustedObjectKeys(userId).forEach(key -> objects.add(
                new AccountDeletionRepository.PendingObject(key, AccountDeletionObjectKind.TRUSTED)));
        for (AccountDeletionRepository.OwnedUpload upload : deletions.findOwnedUploads(userId)) {
            objects.add(new AccountDeletionRepository.PendingObject(upload.quarantineObjectKey(),
                    AccountDeletionObjectKind.QUARANTINE));
            objects.add(new AccountDeletionRepository.PendingObject(uploadStorage.trustedKeyFor(upload.id()),
                    AccountDeletionObjectKind.TRUSTED));
        }
        deletions.captureObjects(deletionId, List.copyOf(objects));
    }
}
