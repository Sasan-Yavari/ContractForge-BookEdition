package io.contractforge.backend.infrastructure.accountdeletion;

import io.contractforge.backend.application.accountdeletion.AccountDeletionQueuePort;
import io.contractforge.backend.application.accountdeletion.AccountDeletionRepository;
import io.contractforge.backend.application.accountdeletion.AccountDeletionQueueProperties;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries queue publication from durable DB state if the first send or process crashes. */
@Component
@Profile("!test")
public final class AccountDeletionQueueDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(AccountDeletionQueueDispatcher.class);

    private final AccountDeletionRepository deletions;
    private final AccountDeletionQueuePort queue;
    private final AccountDeletionQueueProperties properties;
    private final Clock clock;

    public AccountDeletionQueueDispatcher(AccountDeletionRepository deletions, AccountDeletionQueuePort queue,
                                          AccountDeletionQueueProperties properties, Clock clock) {
        this.deletions = deletions;
        this.queue = queue;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${contractforge.account-deletion.queue.poll-seconds:5}000")
    public void dispatch() {
        if (!queue.configured()) return;
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (UUID deletionId : deletions.findReadyToDispatch(now, properties.dispatchBatchSize())) {
            UUID token = UUID.randomUUID();
            if (!deletions.claimDispatch(deletionId, token, now, now.plus(properties.dispatchLease()))) continue;
            try {
                queue.publish(deletionId);
                deletions.markEnqueued(deletionId, token, OffsetDateTime.now(clock));
            } catch (RuntimeException failure) {
                deletions.releaseDispatch(deletionId, token);
                LOG.atWarn()
                        .addKeyValue("event_type", "account_deletion_queue_publish_failed")
                        .addKeyValue("deletion_id", deletionId)
                        .log("account_deletion_queue_publish_failed");
            }
        }
    }
}
