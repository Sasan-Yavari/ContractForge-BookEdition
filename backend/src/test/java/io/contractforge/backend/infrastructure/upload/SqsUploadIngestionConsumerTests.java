package io.contractforge.backend.infrastructure.upload;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.upload.UploadIngestionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

class SqsUploadIngestionConsumerTests {
    private static final String QUEUE = "https://sqs.eu-west-1.amazonaws.com/000000000000/ingestion";
    private static final UUID UPLOAD_ID = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final String KEY = "quarantine/incoming/" + UPLOAD_ID;
    private static final String BODY = "{\"Records\":[{\"eventSource\":\"aws:s3\",\"eventName\":\"ObjectCreated:Put\","
            + "\"s3\":{\"bucket\":{\"name\":\"documents-dev\"},\"object\":{\"key\":\"" + KEY + "\"}}}]}";

    @Test
    void acknowledgesAfterApplicationIngestionSucceeds() {
        UploadIngestionService ingestion = mock(UploadIngestionService.class);
        SqsClient sqs = receiving(BODY);
        consumer(ingestion, sqs).poll();

        verify(ingestion).process(new UploadIngestionService.ObjectCreatedCommand(UPLOAD_ID, KEY));
        verify(sqs).deleteMessage(DeleteMessageRequest.builder().queueUrl(QUEUE).receiptHandle("receipt").build());
    }

    @Test
    void leavesPendingOrTransientGuardDutyResultVisibleForRedelivery() {
        UploadIngestionService ingestion = mock(UploadIngestionService.class);
        doThrow(new UploadIngestionService.RetryableIngestionException()).when(ingestion).process(any());
        SqsClient sqs = receiving(BODY);

        assertThatThrownBy(() -> consumer(ingestion, sqs).poll())
                .isInstanceOf(UploadIngestionService.RetryableIngestionException.class);
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void acknowledgesTerminalApplicationRejection() {
        UploadIngestionService ingestion = mock(UploadIngestionService.class);
        doThrow(new UploadIngestionService.UploadRejectedException()).when(ingestion).process(any());
        SqsClient sqs = receiving(BODY);

        consumer(ingestion, sqs).poll();

        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void poisonMessageIsNotAcknowledgedAndCanReachConfiguredRedrivePolicy() {
        UploadIngestionService ingestion = mock(UploadIngestionService.class);
        SqsClient sqs = receiving("{\"Records\":[]}");

        assertThatThrownBy(() -> consumer(ingestion, sqs).poll())
                .isInstanceOf(S3ObjectCreatedEventParser.MalformedS3ObjectCreatedEventException.class);
        verify(ingestion, never()).process(any());
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void duplicateDeliveryIsPassedToIdempotentApplicationSeamAndAcknowledgedEachTime() {
        UploadIngestionService ingestion = mock(UploadIngestionService.class);
        SqsClient sqs = receivingTwice(BODY);
        SqsUploadIngestionConsumer consumer = consumer(ingestion, sqs);

        consumer.poll();
        consumer.poll();

        verify(ingestion, times(2)).process(new UploadIngestionService.ObjectCreatedCommand(UPLOAD_ID, KEY));
        verify(sqs, times(2)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void productionConfigurationRequiresAnIngestionQueueAndBucket() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new UploadIngestionQueueProperties("", 5, "documents-dev"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new UploadIngestionQueueProperties(QUEUE, 5, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SqsUploadIngestionConsumer consumer(UploadIngestionService ingestion, SqsClient sqs) {
        return new SqsUploadIngestionConsumer(ingestion, sqs,
                new UploadIngestionQueueProperties(QUEUE, 5, "documents-dev"), new ObjectMapper());
    }

    private SqsClient receiving(String body) {
        SqsClient sqs = mock(SqsClient.class);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(response(body));
        return sqs;
    }

    private SqsClient receivingTwice(String body) {
        SqsClient sqs = mock(SqsClient.class);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(response(body), response(body));
        return sqs;
    }

    private ReceiveMessageResponse response(String body) {
        return ReceiveMessageResponse.builder().messages(Message.builder().body(body).receiptHandle("receipt").build())
                .build();
    }
}
