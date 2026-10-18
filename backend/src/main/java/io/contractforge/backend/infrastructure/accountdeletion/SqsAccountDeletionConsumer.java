package io.contractforge.backend.infrastructure.accountdeletion;

import io.contractforge.backend.application.accountdeletion.AccountDeletionEnvelopeSerializer;
import io.contractforge.backend.application.accountdeletion.AccountDeletionWorker;
import io.contractforge.backend.application.accountdeletion.AccountDeletionQueueProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/** Failed jobs and poison envelopes remain unacknowledged for SQS retry and redrive. */
@Component
@Profile("!test")
public final class SqsAccountDeletionConsumer {
    private final AccountDeletionWorker worker;
    private final SqsClient sqs;
    private final AccountDeletionQueueProperties properties;
    private final AccountDeletionEnvelopeSerializer serializer;

    public SqsAccountDeletionConsumer(AccountDeletionWorker worker, SqsClient sqs,
                                      AccountDeletionQueueProperties properties,
                                      AccountDeletionEnvelopeSerializer serializer) {
        this.worker = worker;
        this.sqs = sqs;
        this.properties = properties;
        this.serializer = serializer;
    }

    @Scheduled(fixedDelayString = "${contractforge.account-deletion.queue.poll-seconds:5}000")
    public void poll() {
        if (properties.queueUrl() == null || properties.queueUrl().isBlank()) return;
        sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(properties.queueUrl()).maxNumberOfMessages(1)
                .waitTimeSeconds(Math.max(1, Math.min(20, properties.pollSeconds()))).build())
                .messages().forEach(this::handle);
    }

    private void handle(Message message) {
        worker.process(serializer.parse(message.body()));
        sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(properties.queueUrl())
                .receiptHandle(message.receiptHandle()).build());
    }
}
