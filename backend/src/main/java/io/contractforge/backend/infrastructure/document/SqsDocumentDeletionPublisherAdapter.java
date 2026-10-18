package io.contractforge.backend.infrastructure.document;

import io.contractforge.backend.application.document.DocumentDeletionEnvelopeSerializer;
import io.contractforge.backend.application.document.DocumentDeletionQueuePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Component
@Profile("!test")
final class SqsDocumentDeletionPublisherAdapter implements DocumentDeletionQueuePort {
    private static final Logger LOG = LoggerFactory.getLogger(SqsDocumentDeletionPublisherAdapter.class);

    private final SqsClient sqs;
    private final DocumentDeletionQueueProperties properties;
    private final DocumentDeletionEnvelopeSerializer serializer;

    SqsDocumentDeletionPublisherAdapter(SqsClient sqs, DocumentDeletionQueueProperties properties,
                                        DocumentDeletionEnvelopeSerializer serializer) {
        this.sqs = sqs; this.properties = properties; this.serializer = serializer;
    }

    @Override
    public void publish(DocumentDeletionMessage message) {
        try {
            if (properties.queueUrl() == null || properties.queueUrl().isBlank()) {
                throw new IllegalStateException("Document deletion queue is not configured");
            }
            sqs.sendMessage(SendMessageRequest.builder().queueUrl(properties.queueUrl())
                    .messageBody(serializer.serialize(message)).build());
        } catch (RuntimeException failure) {
            LOG.atError()
                    .addKeyValue("event_type", "document_deletion_publication_failed")
                    .addKeyValue("failure_code", failure.getClass().getSimpleName())
                    .log("document_deletion_publication_failed");
            throw failure;
        }
    }
}
