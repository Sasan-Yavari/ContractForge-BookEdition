package io.contractforge.backend.application.accounting;

import io.contractforge.backend.application.port.RepositoryPorts.UsageRecordRepository;
import io.contractforge.backend.application.port.ActiveAccountWritePort;
import io.contractforge.backend.domain.model.PersistenceModels.UsageRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Isolated accounting persistence boundary; it has no dependency on document processing outcome. */
@Service
@Profile("!test")
public class AccountingWorker {
    private final UsageRecordRepository usageRecords;
    private final ActiveAccountWritePort activeAccounts;
    private final ProviderUsageMetrics metrics;

    public AccountingWorker(UsageRecordRepository usageRecords) { this(usageRecords, null, usage -> { }); }

    @Autowired
    public AccountingWorker(UsageRecordRepository usageRecords, ActiveAccountWritePort activeAccounts,
                           ProviderUsageMetrics metrics) {
        this.usageRecords = usageRecords;
        this.activeAccounts = activeAccounts;
        this.metrics = metrics;
    }

    @Transactional
    public void consume(AccountingPort.UsageEvent event) {
        if (activeAccounts != null && !activeAccounts.lockActiveUser(event.userId())) return;
        int inserted = usageRecords.saveIfAbsent(new UsageRecord(event.usageEventId(), event.userId(), event.documentId(),
                event.operation(), event.provider(), event.service(), event.usageData(), event.costAmount(),
                event.currency(), event.usageEventId(), event.occurredAt(), event.costStatus(), event.pricingVersion()));
        if (inserted == 1) {
            recordAfterCommit(new ProviderUsageMetrics.Usage(event.provider(), event.operation(), event.service(),
                    event.costStatus(), event.costAmount(), event.currency()));
        }
    }

    private void recordAfterCommit(ProviderUsageMetrics.Usage usage) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            metrics.record(usage);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                metrics.record(usage);
            }
        });
    }
}
