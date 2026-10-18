package io.contractforge.backend.application.notification;

import io.contractforge.backend.application.identity.TrustedEmailAddressNormalizer;
import io.contractforge.backend.application.port.RepositoryPorts.NotificationDeliveryRepository;
import io.contractforge.backend.domain.model.PersistenceModels.NotificationDelivery;
import io.contractforge.backend.domain.model.PersistenceModels.NotificationDeliveryWork;
import io.contractforge.backend.domain.model.NotificationFailureCode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Owns idempotent application delivery semantics; queue and SES remain adapters. */
@Service
@Profile("!test")
public final class NotificationDeliveryWorker {
    private final NotificationDeliveryRepository deliveries;
    private final RecommendationNotificationViewFactory viewFactory;
    private final EmailRenderer renderer;
    private final EmailDeliveryPort email;
    private final NotificationEmailProperties emailProperties;
    private final NotificationDeliveryProperties properties;
    private final NotificationDeliveryMetrics metrics;
    private final Clock clock;

    public NotificationDeliveryWorker(NotificationDeliveryRepository deliveries,
                                      RecommendationNotificationViewFactory viewFactory,
                                      EmailRenderer renderer, EmailDeliveryPort email,
                                      NotificationEmailProperties emailProperties,
                                      NotificationDeliveryProperties properties,
                                      NotificationDeliveryMetrics metrics, Clock clock) {
        this.deliveries = deliveries;
        this.viewFactory = viewFactory;
        this.renderer = renderer;
        this.email = email;
        this.emailProperties = emailProperties;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    public Outcome consume(NotificationQueuePort.NotificationDeliveryMessage message) {
        if (!emailProperties.enabled()) {
            return Outcome.ACKNOWLEDGED;
        }
        if (!properties.configured()) {
            throw new IllegalStateException("Notification delivery lease is not configured");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        deliveries.recoverExpiredClaims(now);
        UUID claimToken = UUID.randomUUID();
        Optional<NotificationDeliveryWork> claimed = deliveries.claim(message.deliveryId(), message.notificationId(),
                now, now.plus(properties.leaseDuration()), claimToken);
        if (claimed.isEmpty()) {
            record(message.deliveryId(), Outcome.ACKNOWLEDGED, null, now);
            return Outcome.ACKNOWLEDGED;
        }

        NotificationDeliveryWork work = claimed.orElseThrow();
        NotificationDelivery delivery = work.delivery();
        Optional<String> destination = TrustedEmailAddressNormalizer.normalize(delivery.destination(), true);
        if (destination.isEmpty()) {
            return fail(work, claimToken, now, NotificationFailureCode.RECIPIENT_UNAVAILABLE);
        }

        EmailRenderer.RenderedEmail rendered;
        try {
            rendered = renderer.render(viewFactory.create(work.notification()));
        } catch (RuntimeException failure) {
            return fail(work, claimToken, now, NotificationFailureCode.RENDERING_FAILED);
        }

        // Revalidate the source immediately before crossing the external-send boundary.
        if (deliveries.findClaimed(delivery.id(), claimToken, now).isEmpty()) {
            return acknowledge(work, now);
        }

        try {
            email.send(new EmailDeliveryPort.EmailMessage(destination.orElseThrow(), rendered.subject(), rendered.body()));
            if (deliveries.markDelivered(delivery.id(), claimToken, now) == 0) {
                return acknowledge(work, now);
            }
            record(delivery.id(), Outcome.DELIVERED, delivery, now);
            return Outcome.DELIVERED;
        } catch (EmailDeliveryException failure) {
            NotificationFailureCode code = failureCode(failure.failureCode(), failure.retryable()
                    ? NotificationFailureCode.EMAIL_PROVIDER_UNAVAILABLE : NotificationFailureCode.EMAIL_REJECTED);
            return failure.retryable() && !exhausted(delivery.attemptCount())
                    ? retry(work, claimToken, now, code)
                    : fail(work, claimToken, now, failure.retryable() ? NotificationFailureCode.DELIVERY_ATTEMPTS_EXHAUSTED : code);
        } catch (RuntimeException failure) {
            return !exhausted(delivery.attemptCount())
                    ? retry(work, claimToken, now, NotificationFailureCode.EMAIL_PROVIDER_UNAVAILABLE)
                    : fail(work, claimToken, now, NotificationFailureCode.DELIVERY_ATTEMPTS_EXHAUSTED);
        }
    }

    private boolean exhausted(int attemptCount) {
        return attemptCount >= properties.maxAttempts();
    }

    private Outcome retry(NotificationDeliveryWork work, UUID claimToken, OffsetDateTime now,
                          NotificationFailureCode code) {
        if (deliveries.returnToQueued(work.delivery().id(), claimToken, now, code.name()) == 0) {
            return acknowledge(work, now);
        }
        record(work.delivery().id(), Outcome.RETRYABLE_FAILURE, work.delivery(), now);
        return Outcome.RETRYABLE_FAILURE;
    }

    private Outcome fail(NotificationDeliveryWork work, UUID claimToken, OffsetDateTime now,
                         NotificationFailureCode code) {
        if (deliveries.markFailed(work.delivery().id(), claimToken, now, code.name()) == 0) {
            return acknowledge(work, now);
        }
        record(work.delivery().id(), Outcome.DEAD_LETTER, work.delivery(), now);
        return Outcome.DEAD_LETTER;
    }

    private Outcome acknowledge(NotificationDeliveryWork work, OffsetDateTime now) {
        record(work.delivery().id(), Outcome.ACKNOWLEDGED, work.delivery(), now);
        return Outcome.ACKNOWLEDGED;
    }

    private void record(UUID deliveryId, Outcome outcome, NotificationDelivery delivery, OffsetDateTime now) {
        int attemptCount = delivery == null ? 0 : delivery.attemptCount();
        Duration queueAge = delivery == null ? Duration.ZERO : nonNegative(Duration.between(delivery.createdAt(), now));
        Duration latency = delivery == null || delivery.lastAttemptAt() == null
                ? Duration.ZERO : nonNegative(Duration.between(delivery.lastAttemptAt(), now));
        NotificationDeliveryMetrics.Outcome metricOutcome = switch (outcome) {
            case ACKNOWLEDGED -> NotificationDeliveryMetrics.Outcome.ACKNOWLEDGED;
            case DELIVERED -> NotificationDeliveryMetrics.Outcome.DELIVERED;
            case RETRYABLE_FAILURE -> NotificationDeliveryMetrics.Outcome.RETRYABLE_FAILURE;
            case DEAD_LETTER -> NotificationDeliveryMetrics.Outcome.DLQ;
        };
        metrics.record(new NotificationDeliveryMetrics.DeliveryOutcome(deliveryId, metricOutcome, attemptCount,
                queueAge, latency));
    }

    private Duration nonNegative(Duration value) {
        return value.isNegative() ? Duration.ZERO : value;
    }

    private NotificationFailureCode failureCode(String value, NotificationFailureCode fallback) {
        try {
            return value == null ? fallback : NotificationFailureCode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    public enum Outcome {
        ACKNOWLEDGED, DELIVERED, RETRYABLE_FAILURE, DEAD_LETTER
    }
}
