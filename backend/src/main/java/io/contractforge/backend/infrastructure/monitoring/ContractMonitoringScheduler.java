package io.contractforge.backend.infrastructure.monitoring;

import io.contractforge.backend.application.monitoring.ContractMonitoringExecutionService;
import io.contractforge.backend.application.monitoring.ContractMonitoringExecutionService.MonitoringResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Infrastructure trigger only; monitoring eligibility and persistence remain application-owned. */
public final class ContractMonitoringScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ContractMonitoringScheduler.class);

    private final ContractMonitoringExecutionService execution;

    public ContractMonitoringScheduler(ContractMonitoringExecutionService execution) {
        this.execution = execution;
    }

    @Scheduled(cron = "${contractforge.monitoring.schedule.cron:0 0 2 * * *}",
            zone = "${contractforge.monitoring.schedule.zone:UTC}")
    public void run() {
        LOG.atInfo()
                .addKeyValue("event_type", "contract_monitoring_scheduled_run_started")
                .log("contract_monitoring_scheduled_run_started");
        try {
            MonitoringResult result = execution.execute();
            LOG.atInfo()
                    .addKeyValue("event_type", "contract_monitoring_scheduled_run_completed")
                    .addKeyValue("eligible_contracts", result.eligibleContracts())
                    .addKeyValue("persisted_events", result.persistedEvents())
                    .addKeyValue("persisted_recommendations", result.persistedRecommendations())
                    .log("contract_monitoring_scheduled_run_completed");
        } catch (RuntimeException ignored) {
            LOG.atError()
                    .addKeyValue("event_type", "contract_monitoring_scheduled_run_failed")
                    .addKeyValue("failure_code", "MONITORING_EXECUTION_FAILED")
                    .log("contract_monitoring_scheduled_run_failed");
        }
    }
}
