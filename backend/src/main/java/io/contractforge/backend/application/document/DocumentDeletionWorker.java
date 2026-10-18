package io.contractforge.backend.application.document;

import io.contractforge.backend.application.port.ObjectStoragePort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Performs required cleanup; exceptions deliberately escape so SQS can retry the message. */
@Service
@Profile("!test")
public final class DocumentDeletionWorker {
    private final ObjectStoragePort storage;

    public DocumentDeletionWorker(ObjectStoragePort storage) {
        this.storage = storage;
    }

    public void consume(DocumentDeletionQueuePort.DocumentDeletionMessage message) {
        storage.deletePrivateObject(message.storageKey());
    }
}
