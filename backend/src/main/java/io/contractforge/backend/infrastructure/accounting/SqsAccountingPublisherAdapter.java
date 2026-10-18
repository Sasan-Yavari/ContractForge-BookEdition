package io.contractforge.backend.infrastructure.accounting;

import io.contractforge.backend.application.accounting.AccountingMessageCodec;
import io.contractforge.backend.application.accounting.AccountingPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Component
@Profile("!test")
final class SqsAccountingPublisherAdapter implements AccountingPort {
    private static final Logger LOG = LoggerFactory.getLogger(SqsAccountingPublisherAdapter.class);

    private final SqsClient sqs;
    private final AccountingQueueProperties properties;

    SqsAccountingPublisherAdapter(SqsClient sqs, AccountingQueueProperties properties) {
        this.sqs = sqs; this.properties = properties;
    }

    @Override
    public void publish(UsageEvent event) {
        if (properties.queueUrl() == null || properties.queueUrl().isBlank()) {
            throw new IllegalArgumentException("Accounting publication is not configured");
        }
        try {
            sqs.sendMessage(SendMessageRequest.builder().queueUrl(properties.queueUrl())
                    .messageBody(AccountingMessageCodec.encode(event)).build());
        } catch (SdkException failure) {
            // Accounting is an asynchronous side effect; an unavailable queue must not repeat paid document work.
            LOG.atWarn()
                    .addKeyValue("event_type", "accounting_usage_publication_failed")
                    .addKeyValue("usage_event_id", event.usageEventId())
                    .addKeyValue("failure_code", failure.getClass().getSimpleName())
                    .log("accounting_usage_publication_failed");
        }
    }
}
