package io.contractforge.backend.infrastructure.processing;

import io.contractforge.backend.application.processing.ProcessingOrchestratorService;
import io.contractforge.backend.application.processing.ProcessingMessageCodec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** Queue-runtime adapter; acknowledgement remains owned by the SQS listener runtime. */
@Component
@Profile("!test")
public final class SqsProcessingConsumer {
    private final ProcessingOrchestratorService orchestration;
    private final SqsClient sqs;
    private final ProcessingQueueProperties properties;

    public SqsProcessingConsumer(ProcessingOrchestratorService orchestration, SqsClient sqs,
                                ProcessingQueueProperties properties) {
        this.orchestration = orchestration;
        this.sqs = sqs;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${contractforge.processing.queue.poll-seconds:5}000")
    public void poll() {
        if (properties.queueUrl() == null || properties.queueUrl().isBlank()) {
            return;
        }
        sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(properties.queueUrl()).maxNumberOfMessages(1)
                .waitTimeSeconds(Math.max(1, Math.min(20, properties.pollSeconds()))).build()).messages().forEach(message -> {
            try {
                if (consume(message.body()) != ProcessingOrchestratorService.Outcome.IN_PROGRESS) {
                    sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(properties.queueUrl())
                            .receiptHandle(message.receiptHandle()).build());
                }
            } catch (io.contractforge.backend.application.processing.PermanentProcessingException
                    | ProcessingMessageCodec.MalformedProcessingMessageException failure) {
                if (properties.dlqUrl() == null || properties.dlqUrl().isBlank()) {
                    throw new IllegalStateException("Processing DLQ is not configured", failure);
                }
                sqs.sendMessage(SendMessageRequest.builder().queueUrl(properties.dlqUrl()).messageBody(message.body()).build());
                sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(properties.queueUrl())
                        .receiptHandle(message.receiptHandle()).build());
            }
        });
    }

    public ProcessingOrchestratorService.Outcome consume(String messageBody) {
        return orchestration.consume(messageBody);
    }
}
