package io.contractforge.backend.application.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.contractforge.backend.application.port.ActiveAccountWritePort;
import io.contractforge.backend.application.port.RepositoryPorts.UsageRecordRepository;
import io.contractforge.backend.application.accounting.AccountingPort.UsageEvent;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import io.contractforge.backend.domain.model.UsageCostStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountingWorkerMetricsTests {
    private static final UUID USER_ID = UUID.fromString("81000000-0000-0000-0000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("81000000-0000-0000-0000-000000000002");
    private static final UUID USAGE_EVENT_ID = UUID.fromString("81000000-0000-0000-0000-000000000003");

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void emitsOnlyLowCardinalityUsageMetadataAfterNewDurableInsert() {
        UsageRecordRepository records = mock(UsageRecordRepository.class);
        ActiveAccountWritePort accounts = mock(ActiveAccountWritePort.class);
        List<ProviderUsageMetrics.Usage> emitted = new ArrayList<>();
        when(accounts.lockActiveUser(USER_ID)).thenReturn(true);
        when(records.saveIfAbsent(any())).thenReturn(1);
        AccountingWorker worker = new AccountingWorker(records, accounts, emitted::add);

        worker.consume(event());

        assertThat(emitted).containsExactly(new ProviderUsageMetrics.Usage("aws", "OCR",
                "textract-detect-document-text", UsageCostStatus.CALCULATED, new BigDecimal("0.0015"), "USD"));
    }

    @Test
    void duplicateUsageDeliveryDoesNotEmitUsageMetrics() {
        UsageRecordRepository records = mock(UsageRecordRepository.class);
        ActiveAccountWritePort accounts = mock(ActiveAccountWritePort.class);
        List<ProviderUsageMetrics.Usage> emitted = new ArrayList<>();
        when(accounts.lockActiveUser(USER_ID)).thenReturn(true);
        when(records.saveIfAbsent(any())).thenReturn(0);
        AccountingWorker worker = new AccountingWorker(records, accounts, emitted::add);

        worker.consume(event());
        worker.consume(event());

        assertThat(emitted).isEmpty();
    }

    @Test
    void deletedAccountDoesNotPersistOrEmitUsageMetrics() {
        UsageRecordRepository records = mock(UsageRecordRepository.class);
        ActiveAccountWritePort accounts = mock(ActiveAccountWritePort.class);
        List<ProviderUsageMetrics.Usage> emitted = new ArrayList<>();
        when(accounts.lockActiveUser(USER_ID)).thenReturn(false);
        AccountingWorker worker = new AccountingWorker(records, accounts, emitted::add);

        worker.consume(event());

        verify(records, never()).saveIfAbsent(any());
        assertThat(emitted).isEmpty();
    }

    @Test
    void emitsUsageMetricOnlyAfterTransactionCommits() {
        UsageRecordRepository records = mock(UsageRecordRepository.class);
        ActiveAccountWritePort accounts = mock(ActiveAccountWritePort.class);
        List<ProviderUsageMetrics.Usage> emitted = new ArrayList<>();
        when(accounts.lockActiveUser(USER_ID)).thenReturn(true);
        when(records.saveIfAbsent(any())).thenReturn(1);
        AccountingWorker worker = new AccountingWorker(records, accounts, emitted::add);

        TransactionSynchronizationManager.initSynchronization();
        worker.consume(event());

        assertThat(emitted).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        assertThat(emitted).hasSize(1);
    }

    private UsageEvent event() {
        return new UsageEvent(USAGE_EVENT_ID, USER_ID, DOCUMENT_ID, "OCR", "aws", "textract-detect-document-text",
                new JsonValue("{\"pages\":1}"), new BigDecimal("0.0015"), "USD",
                OffsetDateTime.parse("2026-10-01T00:00:00Z"), UsageCostStatus.CALCULATED,
                "aws-textract-detect-document-text-2026-09-01");
    }
}
