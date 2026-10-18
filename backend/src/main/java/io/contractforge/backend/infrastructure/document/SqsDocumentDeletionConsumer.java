package io.contractforge.backend.infrastructure.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.contractforge.backend.application.document.DocumentDeletionEnvelopeSerializer;
import io.contractforge.backend.application.document.DocumentDeletionQueuePort;
import io.contractforge.backend.application.document.DocumentDeletionWorker;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/** A failed required cleanup is left unacknowledged for SQS retry/redrive. */
@Component
@Profile("!test")
public final class SqsDocumentDeletionConsumer {
    private final DocumentDeletionWorker worker;
    private final SqsClient sqs;
    private final DocumentDeletionQueueProperties properties;
    private final ObjectMapper mapper;

    public SqsDocumentDeletionConsumer(DocumentDeletionWorker worker, SqsClient sqs,
                                       DocumentDeletionQueueProperties properties, ObjectMapper mapper) {
        this.worker = worker; this.sqs = sqs; this.properties = properties; this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${contractforge.document-deletion.queue.poll-seconds:5}000")
    public void poll() {
        if (properties.queueUrl() == null || properties.queueUrl().isBlank()) return;
        sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(properties.queueUrl()).maxNumberOfMessages(1)
                .waitTimeSeconds(Math.max(1, Math.min(20, properties.pollSeconds()))).build()).messages().forEach(this::handle);
    }

    public void consume(String body) { worker.consume(decode(body)); }

    private void handle(Message message) {
        consume(message.body());
        sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(properties.queueUrl())
                .receiptHandle(message.receiptHandle()).build());
    }

    private DocumentDeletionQueuePort.DocumentDeletionMessage decode(String body) {
        try {
            JsonNode value = mapper.readTree(body);
            if (value == null || !value.isObject() || value.size() != 3
                    || value.path("schemaVersion").intValue() != DocumentDeletionEnvelopeSerializer.SCHEMA_VERSION
                    || !DocumentDeletionEnvelopeSerializer.MESSAGE_TYPE.equals(value.path("messageType").textValue())) {
                throw new IllegalArgumentException("Unsupported document deletion envelope");
            }
            String key = value.path("storageKey").textValue();
            return new DocumentDeletionQueuePort.DocumentDeletionMessage(key);
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new IllegalArgumentException("Malformed document deletion envelope", failure);
        }
    }
}
