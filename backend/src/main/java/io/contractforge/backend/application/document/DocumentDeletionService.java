package io.contractforge.backend.application.document;

import io.contractforge.backend.application.port.RepositoryPorts.DocumentRepository;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owns the transactional database deletion and defers external cleanup until commit. */
@Service
@Profile("!test")
public class DocumentDeletionService {
    private final DocumentRepository documents;
    private final DocumentDeletionQueuePort queue;

    public DocumentDeletionService(DocumentRepository documents, DocumentDeletionQueuePort queue) {
        this.documents = documents;
        this.queue = queue;
    }

    @Transactional
    public void delete(AuthenticatedApplicationUser user, UUID documentId) {
        if (user == null || documentId == null) {
            throw new DocumentNotFoundException();
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Document deletion requires an active transaction");
        }
        String storageKey = documents.deleteOwnedById(documentId, user.userId())
                .orElseThrow(DocumentNotFoundException::new);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                queue.publish(new DocumentDeletionQueuePort.DocumentDeletionMessage(storageKey));
            }
        });
    }

    public static final class DocumentNotFoundException extends RuntimeException { }
}
