package io.contractforge.backend.application.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import io.contractforge.backend.application.port.RepositoryPorts.DocumentRepository;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DocumentDeletionServiceTests {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID DOCUMENT = UUID.randomUUID();
    private static final String KEY = "private/trusted/documents/document";

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesOnlyAfterCommitWithTheSafeStorageIdentity() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentDeletionQueuePort queue = mock(DocumentDeletionQueuePort.class);
        when(documents.deleteOwnedById(DOCUMENT, USER)).thenReturn(Optional.of(KEY));
        DocumentDeletionService service = new DocumentDeletionService(documents, queue);
        TransactionSynchronizationManager.initSynchronization();

        service.delete(new AuthenticatedApplicationUser(USER), DOCUMENT);
        verify(queue, never()).publish(org.mockito.ArgumentMatchers.any());

        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

        verify(queue).publish(new DocumentDeletionQueuePort.DocumentDeletionMessage(KEY));
    }

    @Test
    void rollbackDoesNotPublishCleanup() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentDeletionQueuePort queue = mock(DocumentDeletionQueuePort.class);
        when(documents.deleteOwnedById(DOCUMENT, USER)).thenReturn(Optional.of(KEY));
        DocumentDeletionService service = new DocumentDeletionService(documents, queue);
        TransactionSynchronizationManager.initSynchronization();

        service.delete(new AuthenticatedApplicationUser(USER), DOCUMENT);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(1));

        verify(queue, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void crossOwnerAndMissingDocumentsAreIndistinguishable() {
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentDeletionService service = new DocumentDeletionService(documents, mock(DocumentDeletionQueuePort.class));
        TransactionSynchronizationManager.initSynchronization();
        when(documents.deleteOwnedById(DOCUMENT, OTHER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(new AuthenticatedApplicationUser(OTHER), DOCUMENT))
                .isInstanceOf(DocumentDeletionService.DocumentNotFoundException.class);
    }
}
