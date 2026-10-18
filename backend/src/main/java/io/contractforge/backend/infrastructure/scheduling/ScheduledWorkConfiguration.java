package io.contractforge.backend.infrastructure.scheduling;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.annotation.Profile;

/**
 * Provides bounded concurrency for blocking SQS long-poll methods and the monitoring cron.
 * The six queue pollers, monitoring trigger, and optional notification recovery task fit in this pool.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!restore-reconciliation")
@EnableScheduling
public class ScheduledWorkConfiguration {

    static final int SCHEDULED_WORK_POOL_SIZE = 8;

    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(SCHEDULED_WORK_POOL_SIZE);
        scheduler.setThreadNamePrefix("contractforge-scheduled-");
        return scheduler;
    }
}
