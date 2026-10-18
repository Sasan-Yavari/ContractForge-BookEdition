package io.contractforge.backend.infrastructure.accounting;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.contractforge.backend.application.accounting.AccountingPort;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import io.contractforge.backend.domain.model.UsageCostStatus;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;

class SqsAccountingPublisherAdapterTests {
    private static final AccountingPort.UsageEvent EVENT = new AccountingPort.UsageEvent(
            UUID.fromString("43000000-0000-0000-0000-000000000001"),
            UUID.fromString("43000000-0000-0000-0000-000000000002"),
            UUID.fromString("43000000-0000-0000-0000-000000000003"),
            "OCR", "aws", "textract-detect-document-text", new JsonValue("{\"pages\":1}"),
            BigDecimal.ZERO, "USD", OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            UsageCostStatus.UNKNOWN, null);

    @Test
    void sqsSdkFailureDoesNotFailPaidBusinessProcessing() {
        boolean[] sendAttempted = {false};
        SqsClient sqs = sqsClient((proxy, method, args) -> {
            if ("sendMessage".equals(method.getName())) {
                sendAttempted[0] = true;
                throw SdkClientException.create("queue unavailable");
            }
            return defaultValue(method.getReturnType());
        });
        SqsAccountingPublisherAdapter publisher = new SqsAccountingPublisherAdapter(sqs,
                new AccountingQueueProperties("https://sqs.example.test/accounting", "", 5));

        assertThatCode(() -> publisher.publish(EVENT)).doesNotThrowAnyException();

        org.assertj.core.api.Assertions.assertThat(sendAttempted[0]).isTrue();
    }

    @Test
    void missingAccountingQueueConfigurationStillFailsClosed() {
        SqsAccountingPublisherAdapter publisher = new SqsAccountingPublisherAdapter(sqsClient((proxy, method, args) ->
                defaultValue(method.getReturnType())),
                new AccountingQueueProperties(" ", "", 5));

        assertThatThrownBy(() -> publisher.publish(EVENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Accounting publication is not configured");
    }

    private static SqsClient sqsClient(java.lang.reflect.InvocationHandler handler) {
        return (SqsClient) Proxy.newProxyInstance(SqsClient.class.getClassLoader(),
                new Class<?>[] {SqsClient.class}, handler);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType.isPrimitive()) return 0;
        return null;
    }
}
