package io.contractforge.backend.infrastructure.notification;

import io.contractforge.backend.application.notification.NotificationDispatchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Infrastructure trigger for configured recovery; interval remains deployment configuration. */
@Component
@ConditionalOnProperty(name = "contractforge.notification.queue.recovery-enabled", havingValue = "true")
final class NotificationRecoveryScheduler {
    private final NotificationDispatchService dispatch;

    NotificationRecoveryScheduler(NotificationDispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @Scheduled(fixedDelayString = "${contractforge.notification.queue.recovery-delay-ms}")
    void recover() {
        dispatch.recoverQueued();
    }
}
