package io.contractforge.backend.application.accounting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Emits only bounded provider/cost metadata for CloudWatch structured logs and metric filters. */
@Component
@Profile("!test")
public final class LoggingProviderUsageMetrics implements ProviderUsageMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(LoggingProviderUsageMetrics.class);

    @Override
    public void record(Usage usage) {
        LOG.atInfo()
                .addKeyValue("event_type", "provider_usage_recorded")
                .addKeyValue("provider", usage.provider())
                .addKeyValue("operation", usage.operation())
                .addKeyValue("service", usage.service())
                .addKeyValue("cost_status", usage.costStatus().name())
                .addKeyValue("cost_amount", usage.costAmount())
                .addKeyValue("currency", usage.currency())
                .log("provider_usage_recorded");
    }
}
