package io.contractforge.backend.infrastructure.processing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.port.ProcessingPublisherPort;
import io.contractforge.backend.application.port.ProcessingPublisherPort.ProcessingMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** SQS adapter for the minimal document-processing trigger. */
@Component
@Profile("!test")
final class SqsProcessingPublisherAdapter implements ProcessingPublisherPort {
    private final SqsClient sqs;
    private final ProcessingQueueProperties properties;
    private final ObjectMapper json;

    SqsProcessingPublisherAdapter(SqsClient sqs, ProcessingQueueProperties properties, ObjectMapper json) {
        this.sqs = sqs;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public void publish(ProcessingMessage message) {
        if (message == null || message.documentId() == null || properties.queueUrl() == null
                || properties.queueUrl().isBlank()) {
            throw new IllegalArgumentException("Processing publication is not configured");
        }
        String body;
        try {
            body = json.writeValueAsString(new ProcessingEnvelope(message.documentId()));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Processing message could not be serialized", failure);
        }
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(properties.queueUrl()).messageBody(body).build());
    }

    private record ProcessingEnvelope(java.util.UUID documentId) { }
}
