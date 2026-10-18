package io.contractforge.backend.infrastructure.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.contractforge.backend.application.document.DocumentDeletionEnvelopeSerializer;
import io.contractforge.backend.application.document.DocumentDeletionQueuePort.DocumentDeletionMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

class SqsDocumentDeletionPublisherAdapterTests {
    @Test
    void publishesTheVersionedEnvelopeToTheConfiguredQueue() {
        SqsClient sqs = mock(SqsClient.class);
        DocumentDeletionQueueProperties properties = mock(DocumentDeletionQueueProperties.class);
        DocumentDeletionEnvelopeSerializer serializer = mock(DocumentDeletionEnvelopeSerializer.class);
        DocumentDeletionMessage message = new DocumentDeletionMessage("private/trusted/documents/upload-id");
        when(properties.queueUrl()).thenReturn("queue-url");
        when(serializer.serialize(message)).thenReturn("versioned-envelope");

        new SqsDocumentDeletionPublisherAdapter(sqs, properties, serializer).publish(message);

        ArgumentCaptor<SendMessageRequest> request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(request.capture());
        assertThat(request.getValue().queueUrl()).isEqualTo("queue-url");
        assertThat(request.getValue().messageBody()).isEqualTo("versioned-envelope");
    }

    @Test
    void leavesPostCommitPublicationFailureVisibleToTheCaller() {
        SqsClient sqs = mock(SqsClient.class);
        DocumentDeletionQueueProperties properties = mock(DocumentDeletionQueueProperties.class);
        DocumentDeletionEnvelopeSerializer serializer = mock(DocumentDeletionEnvelopeSerializer.class);
        DocumentDeletionMessage message = new DocumentDeletionMessage("private/trusted/documents/upload-id");
        SdkClientException failure = SdkClientException.create("SQS unavailable");
        when(properties.queueUrl()).thenReturn("queue-url");
        when(serializer.serialize(message)).thenReturn("versioned-envelope");
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> new SqsDocumentDeletionPublisherAdapter(sqs, properties, serializer).publish(message))
                .isSameAs(failure);
    }
}
