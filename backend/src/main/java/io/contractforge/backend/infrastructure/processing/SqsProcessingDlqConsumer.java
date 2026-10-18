package io.contractforge.backend.infrastructure.processing;

import io.contractforge.backend.application.processing.ProcessingDlqService;
import io.contractforge.backend.application.processing.ProcessingMessageCodec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;

/** DLQ queue-runtime adapter for exhausted processing deliveries. */
@Component
@Profile("!test")
public final class SqsProcessingDlqConsumer {
    private final ProcessingDlqService dlq;
    private final SqsClient sqs;
    private final ProcessingQueueProperties properties;

    public SqsProcessingDlqConsumer(ProcessingDlqService dlq, SqsClient sqs, ProcessingQueueProperties properties) {
        this.dlq = dlq;
        this.sqs = sqs;
        this.properties = properties;
    }

    public void consume(String messageBody) {
        dlq.handle(ProcessingMessageCodec.decode(messageBody));
    }

    @Scheduled(fixedDelayString = "${contractforge.processing.queue.poll-seconds:5}000")
    public void poll() {
        if (properties.dlqUrl() == null || properties.dlqUrl().isBlank()) {
            return;
        }
        sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(properties.dlqUrl()).maxNumberOfMessages(1)
                .waitTimeSeconds(Math.max(1, Math.min(20, properties.pollSeconds()))).build()).messages().forEach(this::handle);
    }

    private void handle(Message message) {
        try {
            consume(message.body());
        } catch (ProcessingMessageCodec.MalformedProcessingMessageException malformed) {
            // Keep malformed terminal evidence in the DLQ for operator investigation.
            return;
        }
        sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(properties.dlqUrl())
                .receiptHandle(message.receiptHandle()).build());
    }
}
