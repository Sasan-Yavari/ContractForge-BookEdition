package io.contractforge.backend.infrastructure.monitoring;

import io.contractforge.backend.application.monitoring.ContractMonitoringExecutionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Enables the production monitoring trigger without moving monitoring rules into infrastructure. */
@Configuration(proxyBeanMethods = false)
public class MonitoringSchedulingConfiguration {

    @Bean
    @Profile("!test")
    @ConditionalOnProperty(prefix = "contractforge.monitoring.schedule", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    ContractMonitoringScheduler contractMonitoringScheduler(ContractMonitoringExecutionService execution) {
        return new ContractMonitoringScheduler(execution);
    }
}
