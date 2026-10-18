package io.contractforge.backend.application.document;

/** Application boundary for post-commit private-object cleanup. */
public interface DocumentDeletionQueuePort {
    void publish(DocumentDeletionMessage message);

    record DocumentDeletionMessage(String storageKey) {
        public DocumentDeletionMessage {
            if (storageKey == null || storageKey.isBlank()) {
                throw new IllegalArgumentException("Document deletion requires a storage key");
            }
        }
    }
}
