package io.contractforge.backend.infrastructure.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.contractforge.backend.application.accounting.AccountingMessageCodec;
import io.contractforge.backend.application.accounting.AccountingPort;
import io.contractforge.backend.application.accounting.AccountingWorker;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import io.contractforge.backend.domain.model.UsageCostStatus;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

class SqsAccountingConsumerTests {
    private static final AccountingPort.UsageEvent EVENT = new AccountingPort.UsageEvent(
            UUID.fromString("44000000-0000-0000-0000-000000000001"),
            UUID.fromString("44000000-0000-0000-0000-000000000002"),
            UUID.fromString("44000000-0000-0000-0000-000000000003"), "OCR", "aws",
            "textract-detect-document-text", new JsonValue("{\"pages\":1}"), BigDecimal.ZERO,
            "USD", OffsetDateTime.parse("2026-01-01T00:00:00Z"), UsageCostStatus.UNKNOWN, null);

    @Test
    void successfulPersistenceAcknowledgesMessage() {
        Calls calls = new Calls(AccountingMessageCodec.encode(EVENT));
        AccountingPort.UsageEvent[] persisted = {null};
        AccountingWorker worker = new AccountingWorker(null) {
            @Override
            public void consume(AccountingPort.UsageEvent event) { persisted[0] = event; }
        };

        consumer(worker, calls.client(), "dlq").poll();

        assertThat(persisted[0]).isEqualTo(EVENT);
        assertThat(calls.deleted).isTrue();
    }

    @Test
    void persistenceFailureLeavesSourceMessageUnacknowledged() {
        Calls calls = new Calls(AccountingMessageCodec.encode(EVENT));
        AccountingWorker worker = new AccountingWorker(null) {
            @Override
            public void consume(AccountingPort.UsageEvent event) { throw new IllegalStateException("database unavailable"); }
        };

        assertThatThrownBy(() -> consumer(worker, calls.client(), "dlq").poll())
                .isInstanceOf(IllegalStateException.class);

        assertThat(calls.deleted).isFalse();
        assertThat(calls.dlqBody).isNull();
    }

    @Test
    void malformedMessageIsTransferredToDlqBeforeAcknowledgement() {
        String poison = "{\"usageEventId\":\"not-a-uuid\"}";
        Calls calls = new Calls(poison);
        AccountingWorker worker = new AccountingWorker(null);

        consumer(worker, calls.client(), "dlq").poll();

        assertThat(calls.dlqBody).isEqualTo(poison);
        assertThat(calls.deleted).isTrue();
    }

    private SqsAccountingConsumer consumer(AccountingWorker worker, SqsClient sqs, String dlqUrl) {
        return new SqsAccountingConsumer(worker, sqs, new AccountingQueueProperties("queue", dlqUrl, 1));
    }

    private static final class Calls {
        private final String body;
        private String dlqBody;
        private boolean deleted;

        private Calls(String body) { this.body = body; }

        private SqsClient client() {
            return (SqsClient) Proxy.newProxyInstance(SqsClient.class.getClassLoader(),
                    new Class<?>[] {SqsClient.class}, (proxy, method, args) -> {
                        if (method.getName().equals("receiveMessage")) {
                            return ReceiveMessageResponse.builder().messages(Message.builder().body(body)
                                    .receiptHandle("receipt").build()).build();
                        }
                        if (method.getName().equals("sendMessage")) {
                            SendMessageRequest request = (SendMessageRequest) args[0];
                            assertThat(request.queueUrl()).isEqualTo("dlq");
                            dlqBody = request.messageBody();
                            return SendMessageResponse.builder().messageId("dlq-message").build();
                        }
                        if (method.getName().equals("deleteMessage")) {
                            assertThat(((DeleteMessageRequest) args[0]).queueUrl()).isEqualTo("queue");
                            deleted = true;
                            return null;
                        }
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType().isPrimitive()) return 0;
                        return null;
                    });
        }
    }
}
