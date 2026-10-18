package io.contractforge.backend.infrastructure.accountdeletion;

import io.contractforge.backend.application.accountdeletion.AccountDeletionEnvelopeSerializer;
import io.contractforge.backend.application.accountdeletion.AccountDeletionQueuePort;
import io.contractforge.backend.application.accountdeletion.AccountDeletionQueueProperties;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Component
@Profile("!test")
final class SqsAccountDeletionQueueAdapter implements AccountDeletionQueuePort {
    private final SqsClient sqs;
    private final AccountDeletionQueueProperties properties;
    private final AccountDeletionEnvelopeSerializer serializer;

    SqsAccountDeletionQueueAdapter(SqsClient sqs, AccountDeletionQueueProperties properties,
                                   AccountDeletionEnvelopeSerializer serializer) {
        this.sqs = sqs;
        this.properties = properties;
        this.serializer = serializer;
    }

    @Override
    public void publish(UUID deletionId) {
        if (!configured()) throw new IllegalStateException("Account deletion queue is not configured");
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(properties.queueUrl())
                .messageBody(serializer.serialize(deletionId)).build());
    }

    @Override
    public boolean configured() {
        return properties.queueUrl() != null && !properties.queueUrl().isBlank();
    }
}
