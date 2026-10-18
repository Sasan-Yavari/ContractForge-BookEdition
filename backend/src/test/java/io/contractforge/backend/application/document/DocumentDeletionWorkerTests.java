package io.contractforge.backend.application.document;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import io.contractforge.backend.application.port.ObjectStoragePort;
import org.junit.jupiter.api.Test;

class DocumentDeletionWorkerTests {
    @Test
    void delegatesPrivateObjectDeletion() {
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        new DocumentDeletionWorker(storage).consume(new DocumentDeletionQueuePort.DocumentDeletionMessage(
                "private/trusted/documents/object"));
        verify(storage).deletePrivateObject("private/trusted/documents/object");
    }

    @Test
    void storageFailureEscapesSoTheQueueCanRetry() {
        ObjectStoragePort storage = mock(ObjectStoragePort.class);
        doThrow(new IllegalStateException("temporary S3 failure")).when(storage)
                .deletePrivateObject("private/trusted/documents/object");
        assertThatThrownBy(() -> new DocumentDeletionWorker(storage).consume(
                new DocumentDeletionQueuePort.DocumentDeletionMessage("private/trusted/documents/object")))
                .isInstanceOf(IllegalStateException.class);
    }
}
