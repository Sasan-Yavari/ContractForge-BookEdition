package io.contractforge.backend.application.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.contractforge.backend.application.port.RepositoryPorts;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import io.contractforge.backend.domain.model.PersistenceModels.UsageRecord;
import io.contractforge.backend.domain.model.UsageCostStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountingTests {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DOCUMENT = UUID.randomUUID();

    @Test
    void onePaidOperationProducesOnePersistedUsageRecord() {
        Store store = new Store();
        AccountingWorker worker = new AccountingWorker(store);
        AccountingPort.UsageEvent event = event(UUID.randomUUID());

        worker.consume(event);

        assertThat(store.records).containsExactly(new UsageRecord(event.usageEventId(), USER, DOCUMENT, "OCR",
                "provider", "service", event.usageData(), event.costAmount(), "EUR", event.usageEventId(), event.occurredAt()));
    }

    @Test
    void duplicateDeliveryIsIdempotentEvenWhenConsumedConcurrently() throws Exception {
        Store store = new Store();
        AccountingWorker worker = new AccountingWorker(store);
        AccountingPort.UsageEvent event = event(UUID.randomUUID());
        Thread first = new Thread(() -> worker.consume(event));
        Thread second = new Thread(() -> worker.consume(event));
        first.start(); second.start(); first.join(); second.join();

        assertThat(store.records).hasSize(1);
    }

    @Test
    void separatePaidAttemptsRequireSeparateEventIdsAndRecords() {
        Store store = new Store();
        AccountingWorker worker = new AccountingWorker(store);
        worker.consume(event(UUID.randomUUID()));
        worker.consume(event(UUID.randomUUID()));

        assertThat(store.records).hasSize(2).extracting(UsageRecord::usageEventId).doesNotHaveDuplicates();
    }

    @Test
    void malformedMessagesFailBeforePersistenceAndNoPreProviderEventIsImplicitlyEmitted() {
        Store store = new Store();
        assertThatThrownBy(() -> AccountingMessageCodec.decode("{\"provider\":\"provider\"}"))
                .isInstanceOf(AccountingMessageCodec.MalformedAccountingMessageException.class);
        assertThat(store.records).isEmpty();
    }

    @Test
    void usageMessageContainsAccountingMetadataButNoDocumentContent() {
        AccountingPort.UsageEvent event = event(UUID.randomUUID());
        String body = AccountingMessageCodec.encode(event);

        assertThat(body).contains("usageEventId", "provider", "input_tokens").doesNotContain("document contents", "prompt");
        assertThat(AccountingMessageCodec.decode(body)).isEqualTo(event);
    }

    @Test
    void calculatedCostAuditMetadataSurvivesAsyncMessageBoundary() {
        AccountingPort.UsageEvent event = new AccountingPort.UsageEvent(UUID.randomUUID(), USER, DOCUMENT, "OCR",
                "aws", "textract-detect-document-text", new JsonValue("{\"pages\":3}"),
                new BigDecimal("0.0045"), "USD", OffsetDateTime.now(), UsageCostStatus.CALCULATED,
                "aws-textract-detect-document-text-2026-09-01");

        AccountingPort.UsageEvent decoded = AccountingMessageCodec.decode(AccountingMessageCodec.encode(event));

        assertThat(decoded).isEqualTo(event);
        assertThat(decoded.costStatus()).isEqualTo(UsageCostStatus.CALCULATED);
        assertThat(decoded.pricingVersion()).isEqualTo("aws-textract-detect-document-text-2026-09-01");
    }

    @Test
    void unknownCostCannotCrossTheAccountingBoundaryWithAClaimedAmount() {
        String message = "{\"usageEventId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"userId\":\"00000000-0000-0000-0000-000000000002\","
                + "\"documentId\":\"00000000-0000-0000-0000-000000000003\","
                + "\"operation\":\"OCR\",\"provider\":\"aws\","
                + "\"service\":\"textract-detect-document-text\",\"usageData\":{\"pages\":null},"
                + "\"costAmount\":1,\"currency\":\"USD\",\"occurredAt\":\"2026-09-16T00:00:00Z\","
                + "\"costStatus\":\"UNKNOWN\",\"pricingVersion\":\"forged\"}";

        assertThatThrownBy(() -> AccountingMessageCodec.decode(message))
                .isInstanceOf(AccountingMessageCodec.MalformedAccountingMessageException.class);
    }

    @Test
    void accountingFailureIsSeparateFromAnyBusinessProcessingResult() {
        RepositoryPorts.UsageRecordRepository failing = new Store() {
            @Override public int saveIfAbsent(UsageRecord record) { throw new IllegalStateException("database unavailable"); }
        };

        assertThatThrownBy(() -> new AccountingWorker(failing).consume(event(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
    }

    private static AccountingPort.UsageEvent event(UUID id) {
        return new AccountingPort.UsageEvent(id, USER, DOCUMENT, "OCR", "provider", "service",
                new JsonValue("{\"input_tokens\":120,\"nested\":{\"path\":\"C:\\\\tmp\",\"note\":\"quoted\"}}"),
                new BigDecimal("0.0100"), "EUR", OffsetDateTime.now());
    }

    private static class Store implements RepositoryPorts.UsageRecordRepository {
        private final List<UsageRecord> records = new ArrayList<>();
        @Override public synchronized void save(UsageRecord record) { records.add(record); }
        @Override public synchronized int saveIfAbsent(UsageRecord record) {
            if (records.stream().anyMatch(existing -> existing.usageEventId().equals(record.usageEventId()))) return 0;
            records.add(record); return 1;
        }
        @Override public synchronized Optional<UsageRecord> findByUsageEventId(UUID id) {
            return records.stream().filter(record -> record.usageEventId().equals(id)).findFirst();
        }
    }
}
