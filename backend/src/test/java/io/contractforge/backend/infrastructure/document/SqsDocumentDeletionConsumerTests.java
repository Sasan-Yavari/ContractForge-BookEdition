package io.contractforge.backend.infrastructure.document;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.document.DocumentDeletionWorker;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

class SqsDocumentDeletionConsumerTests {
    private static final String QUEUE = "https://sqs.example.test/deletion";
    private static final String BODY = "{\"schemaVersion\":1,\"messageType\":\"DOCUMENT_S3_DELETION\","
            + "\"storageKey\":\"private/trusted/documents/object\"}";

    @Test
    void acknowledgesOnlyAfterWorkerSucceeds() {
        DocumentDeletionWorker worker = mock(DocumentDeletionWorker.class);
        SqsClient sqs = mock(SqsClient.class);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(Message.builder().body(BODY).receiptHandle("receipt").build()).build());
        SqsDocumentDeletionConsumer consumer = new SqsDocumentDeletionConsumer(worker, sqs,
                new DocumentDeletionQueueProperties(QUEUE, 5), new ObjectMapper());

        consumer.poll();

        verify(worker).consume(any());
        verify(sqs).deleteMessage(DeleteMessageRequest.builder().queueUrl(QUEUE).receiptHandle("receipt").build());
    }

    @Test
    void leavesFailedWorkerMessageUnacknowledgedForRetry() {
        DocumentDeletionWorker worker = mock(DocumentDeletionWorker.class);
        doThrow(new IllegalStateException("temporary failure")).when(worker).consume(any());
        SqsClient sqs = mock(SqsClient.class);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(Message.builder().body(BODY).receiptHandle("receipt").build()).build());
        SqsDocumentDeletionConsumer consumer = new SqsDocumentDeletionConsumer(worker, sqs,
                new DocumentDeletionQueueProperties(QUEUE, 5), new ObjectMapper());

        assertThatThrownBy(consumer::poll).isInstanceOf(IllegalStateException.class);
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void rejectsMalformedEnvelope() {
        DocumentDeletionWorker worker = mock(DocumentDeletionWorker.class);
        SqsDocumentDeletionConsumer consumer = new SqsDocumentDeletionConsumer(worker, mock(SqsClient.class),
                new DocumentDeletionQueueProperties(QUEUE, 5), new ObjectMapper());

        assertThatThrownBy(() -> consumer.consume("{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
