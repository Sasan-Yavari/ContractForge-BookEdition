package io.contractforge.backend.infrastructure.upload;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.upload.UploadIngestionService;
import io.contractforge.backend.application.upload.UploadIngestionService.UploadNotFoundException;
import io.contractforge.backend.application.upload.UploadIngestionService.UploadRejectedException;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/** Runs the existing application ingestion seam and leaves retryable or poison messages for SQS redrive. */
@Component
@Profile("aws")
@EnableConfigurationProperties(UploadIngestionQueueProperties.class)
public final class SqsUploadIngestionConsumer {
    private final UploadIngestionService ingestion;
    private final SqsClient sqs;
    private final UploadIngestionQueueProperties properties;
    private final S3ObjectCreatedEventParser parser;

    public SqsUploadIngestionConsumer(UploadIngestionService ingestion, SqsClient sqs,
                                     UploadIngestionQueueProperties properties, ObjectMapper mapper) {
        this.ingestion = ingestion;
        this.sqs = sqs;
        this.properties = properties;
        this.parser = new S3ObjectCreatedEventParser(mapper, properties.bucket());
    }

    @Scheduled(fixedDelayString = "${contractforge.upload.ingestion.queue.poll-seconds:5}000")
    public void poll() {
        if (properties.queueUrl() == null || properties.queueUrl().isBlank()) {
            return;
        }
        sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(properties.queueUrl()).maxNumberOfMessages(1)
                .waitTimeSeconds(Math.max(1, Math.min(20, properties.pollSeconds()))).build())
                .messages().forEach(this::handle);
    }

    void consume(String body) {
        List<UploadIngestionService.ObjectCreatedCommand> commands = parser.parse(body);
        for (UploadIngestionService.ObjectCreatedCommand command : commands) {
            try {
                ingestion.process(command);
            } catch (UploadRejectedException | UploadNotFoundException terminal) {
                // The application has durably rejected/cleaned the upload, or the event cannot identify one.
                // Acknowledge only this terminal application outcome; other failures remain retryable.
            }
        }
    }

    private void handle(Message message) {
        consume(message.body());
        sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(properties.queueUrl())
                .receiptHandle(message.receiptHandle()).build());
    }
}
