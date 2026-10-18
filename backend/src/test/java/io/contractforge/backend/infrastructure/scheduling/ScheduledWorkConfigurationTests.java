package io.contractforge.backend.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class ScheduledWorkConfigurationTests {

    @Test
    void blockingScheduledWorkersCanRunConcurrentlyOnTheBoundedPool() throws InterruptedException {
        ThreadPoolTaskScheduler scheduler = new ScheduledWorkConfiguration().taskScheduler();
        var workersStarted = new CountDownLatch(2);
        var releaseWorkers = new CountDownLatch(1);
        Runnable blockingPoll = () -> {
            workersStarted.countDown();
            try {
                releaseWorkers.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };

        scheduler.initialize();
        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isEqualTo(ScheduledWorkConfiguration.SCHEDULED_WORK_POOL_SIZE);
            Instant startTogether = Instant.now().plusMillis(10);
            scheduler.schedule(blockingPoll, startTogether);
            scheduler.schedule(blockingPoll, startTogether);

            assertThat(workersStarted.await(2, TimeUnit.SECONDS))
                    .as("both blocking queue polls start before either returns")
                    .isTrue();
        } finally {
            releaseWorkers.countDown();
            scheduler.shutdown();
        }
    }
}
