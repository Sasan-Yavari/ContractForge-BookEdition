package io.contractforge.backend.infrastructure.processing;

import static org.assertj.core.api.Assertions.assertThat;

import io.contractforge.backend.application.extraction.DocumentTextExtractionService;
import io.contractforge.backend.application.processing.ProcessingErrorCode;
import io.contractforge.backend.application.processing.ProcessingOrchestratorService;
import io.contractforge.backend.application.processing.RetryableProcessingException;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

class SqsProcessingConsumerTests {
    @Test
    void resourceLimitFailureIsTransferredToDlqAndSourceMessageIsAcknowledged() {
        String body = "{\"documentId\":\"92000000-0000-0000-0000-000000000001\"}";
        ProcessingOrchestratorService orchestration = orchestration(body, () -> {
            throw new DocumentTextExtractionService.ResourceLimitException(ProcessingErrorCode.RESOURCE_LIMIT_OUTPUT_CHARACTERS);
        });
        Calls calls = new Calls(body);

        new SqsProcessingConsumer(orchestration, calls.client(), new ProcessingQueueProperties("queue", "dlq", 1)).poll();

        assertThat(calls.sentBody).isEqualTo(body);
        assertThat(calls.sentQueue).isEqualTo("dlq");
        assertThat(calls.deleted).isTrue();
    }

    @Test
    void activeAttemptRedeliveryRemainsUnacknowledgedForStaleRecovery() {
        String body = "{\"documentId\":\"92000000-0000-0000-0000-000000000001\"}";
        ProcessingOrchestratorService orchestration = orchestration(body,
                () -> ProcessingOrchestratorService.Outcome.IN_PROGRESS);
        Calls calls = new Calls(body);

        new SqsProcessingConsumer(orchestration, calls.client(), new ProcessingQueueProperties("queue", "dlq", 1)).poll();

        assertThat(calls.sentBody).isNull();
        assertThat(calls.deleted).isFalse();
    }

    @Test
    void retryableApplicationFailureRemainsUnacknowledgedForQueueRedelivery() {
        String body = "{\"documentId\":\"92000000-0000-0000-0000-000000000001\"}";
        ProcessingOrchestratorService orchestration = orchestration(body, () -> {
            throw new RetryableProcessingException();
        });
        Calls calls = new Calls(body);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SqsProcessingConsumer(orchestration,
                calls.client(), new ProcessingQueueProperties("queue", "dlq", 1)).poll())
                .isInstanceOf(RetryableProcessingException.class);

        assertThat(calls.sentBody).isNull();
        assertThat(calls.deleted).isFalse();
    }

    private ProcessingOrchestratorService orchestration(String expectedBody,
                                                        java.util.function.Supplier<ProcessingOrchestratorService.Outcome> outcome) {
        return new ProcessingOrchestratorService(null, null, (io.contractforge.backend.application.processing.ProcessingWorkPort) null, null) {
            @Override
            public Outcome consume(String body) {
                assertThat(body).isEqualTo(expectedBody);
                return outcome.get();
            }
        };
    }

    private static final class Calls {
        private final String body;
        private String sentBody;
        private String sentQueue;
        private boolean deleted;

        private Calls(String body) { this.body = body; }

        private SqsClient client() {
            return (SqsClient) Proxy.newProxyInstance(SqsClient.class.getClassLoader(), new Class<?>[] {SqsClient.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("receiveMessage")) {
                            return ReceiveMessageResponse.builder().messages(Message.builder().body(body)
                                    .receiptHandle("receipt").build()).build();
                        }
                        if (method.getName().equals("sendMessage")) {
                            SendMessageRequest request = (SendMessageRequest) args[0];
                            sentBody = request.messageBody();
                            sentQueue = request.queueUrl();
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
