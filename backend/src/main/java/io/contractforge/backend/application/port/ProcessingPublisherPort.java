package io.contractforge.backend.application.port;

import java.util.UUID;

/** Application boundary for the asynchronous document-processing trigger. */
public interface ProcessingPublisherPort {
    void publish(ProcessingMessage message);

    record ProcessingMessage(UUID documentId) {
        public ProcessingMessage {
            if (documentId == null) {
                throw new IllegalArgumentException("Processing message requires a document id");
            }
        }
    }
}
