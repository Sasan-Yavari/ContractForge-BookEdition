package io.contractforge.backend.infrastructure.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import io.contractforge.backend.application.monitoring.ContractMonitoringExecutionService;
import io.contractforge.backend.application.monitoring.ContractMonitoringExecutionService.MonitoringResult;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;

class ContractMonitoringSchedulerTests {

    @Test
    void scheduledTriggerDelegatesOnlyToApplicationExecutionBoundary() {
        RecordingExecution execution = new RecordingExecution();

        new ContractMonitoringScheduler(execution).run();

        assertThat(execution.invocations()).isOne();
    }

    @Test
    void failedRunIsLoggedAndALaterRunStillInvokesTheBoundary() {
        FailingThenSuccessfulExecution execution = new FailingThenSuccessfulExecution();
        ContractMonitoringScheduler scheduler = new ContractMonitoringScheduler(execution);

        scheduler.run();
        scheduler.run();

        assertThat(execution.invocations()).isEqualTo(2);
    }

    @Test
    void triggerUsesExplicitDailyUtcConfiguration() throws NoSuchMethodException {
        Scheduled scheduled = ContractMonitoringScheduler.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${contractforge.monitoring.schedule.cron:0 0 2 * * *}");
        assertThat(scheduled.zone()).isEqualTo("${contractforge.monitoring.schedule.zone:UTC}");
    }

    @Test
    void disabledConfigurationDoesNotRegisterProductionTrigger() {
        new ApplicationContextRunner()
                .withUserConfiguration(MonitoringSchedulingConfiguration.class)
                .withBean(ContractMonitoringExecutionService.class, RecordingExecution::new)
                .withPropertyValues("contractforge.monitoring.schedule.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ContractMonitoringScheduler.class));
    }

    @Test
    void testProfileDoesNotRegisterProductionTrigger() {
        new ApplicationContextRunner()
                .withUserConfiguration(MonitoringSchedulingConfiguration.class)
                .withBean(ContractMonitoringExecutionService.class, RecordingExecution::new)
                .withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context).doesNotHaveBean(ContractMonitoringScheduler.class));
    }

    @Test
    void enabledProductionConfigurationRegistersTriggerByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(MonitoringSchedulingConfiguration.class)
                .withBean(ContractMonitoringExecutionService.class, RecordingExecution::new)
                .run(context -> assertThat(context).hasSingleBean(ContractMonitoringScheduler.class));
    }

    private static class RecordingExecution extends ContractMonitoringExecutionService {
        protected final AtomicInteger invocations = new AtomicInteger();

        RecordingExecution() {
            super(null, null, null, null,
                    new io.contractforge.backend.application.monitoring.ContractRecommendationService(
                            new com.fasterxml.jackson.databind.ObjectMapper()), null,
                    recommendation -> { }, java.time.Clock.systemUTC());
        }

        @Override
        public MonitoringResult execute() {
            invocations.incrementAndGet();
            return new MonitoringResult(OffsetDateTime.parse("2026-09-16T02:00:00Z"), 3, 2, 2);
        }

        int invocations() {
            return invocations.get();
        }
    }

    private static final class FailingThenSuccessfulExecution extends RecordingExecution {
        @Override
        public MonitoringResult execute() {
            int invocation = super.invocations.incrementAndGet();
            if (invocation == 1) {
                throw new IllegalStateException("database unavailable");
            }
            return new MonitoringResult(OffsetDateTime.parse("2026-09-17T02:00:00Z"), 0, 0, 0);
        }
    }
}
