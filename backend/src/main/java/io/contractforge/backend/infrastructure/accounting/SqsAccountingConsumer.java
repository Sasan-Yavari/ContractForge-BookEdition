package io.contractforge.backend.infrastructure.accounting;

import io.contractforge.backend.application.accounting.AccountingMessageCodec;
import io.contractforge.backend.application.accounting.AccountingWorker;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Component
@Profile("!test")
public final class SqsAccountingConsumer {
    private final AccountingWorker worker; private final SqsClient sqs; private final AccountingQueueProperties properties;
    public SqsAccountingConsumer(AccountingWorker worker, SqsClient sqs, AccountingQueueProperties properties) {
        this.worker = worker; this.sqs = sqs; this.properties = properties;
    }
    @Scheduled(fixedDelayString = "${contractforge.accounting.queue.poll-seconds:5}000")
    public void poll() {
        if (properties.queueUrl() == null || properties.queueUrl().isBlank()) return;
        sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(properties.queueUrl()).maxNumberOfMessages(1)
                .waitTimeSeconds(Math.max(1, Math.min(20, properties.pollSeconds()))).build()).messages().forEach(message -> {
            try {
                worker.consume(AccountingMessageCodec.decode(message.body()));
                delete(message.receiptHandle());
            } catch (AccountingMessageCodec.MalformedAccountingMessageException failure) {
                if (properties.dlqUrl() == null || properties.dlqUrl().isBlank()) throw new IllegalStateException("Accounting DLQ is not configured", failure);
                sqs.sendMessage(SendMessageRequest.builder().queueUrl(properties.dlqUrl()).messageBody(message.body()).build());
                delete(message.receiptHandle());
            }
        });
    }
    public void consume(String body) { worker.consume(AccountingMessageCodec.decode(body)); }
    private void delete(String receipt) { sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(properties.queueUrl()).receiptHandle(receipt).build()); }
}
