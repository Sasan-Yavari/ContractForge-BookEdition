package io.contractforge.backend.infrastructure.processing;

import static org.assertj.core.api.Assertions.assertThat;

import io.contractforge.backend.application.port.ProcessingPublisherPort.ProcessingMessage;
import io.contractforge.backend.application.processing.ProcessingDlqService;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

class SqsProcessingDlqConsumerTests {
    @Test
    void terminalDocumentTransitionAcknowledgesDlqMessage() {
        Calls calls = new Calls("{\"documentId\":\"92000000-0000-0000-0000-000000000001\"}");
        ProcessingDlqService service = new ProcessingDlqService(null, null) {
            @Override
            public void handle(ProcessingMessage message) {
                assertThat(message.documentId().toString()).isEqualTo("92000000-0000-0000-0000-000000000001");
            }
        };

        consumer(service, calls.client()).poll();

        assertThat(calls.deleted).isTrue();
    }

    @Test
    void malformedDlqMessageRemainsVisibleForOperatorInvestigation() {
        Calls calls = new Calls("{\"documentId\":\"not-a-uuid\"}");
        ProcessingDlqService service = new ProcessingDlqService(null, null);

        consumer(service, calls.client()).poll();

        assertThat(calls.deleted).isFalse();
    }

    private SqsProcessingDlqConsumer consumer(ProcessingDlqService service, SqsClient sqs) {
        return new SqsProcessingDlqConsumer(service, sqs, new ProcessingQueueProperties("queue", "dlq", 1));
    }

    private static final class Calls {
        private final String body;
        private boolean deleted;

        private Calls(String body) { this.body = body; }

        private SqsClient client() {
            return (SqsClient) Proxy.newProxyInstance(SqsClient.class.getClassLoader(),
                    new Class<?>[] {SqsClient.class}, (proxy, method, args) -> {
                        if (method.getName().equals("receiveMessage")) {
                            return ReceiveMessageResponse.builder().messages(Message.builder().body(body)
                                    .receiptHandle("receipt").build()).build();
                        }
                        if (method.getName().equals("deleteMessage")) {
                            assertThat(((DeleteMessageRequest) args[0]).queueUrl()).isEqualTo("dlq");
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
