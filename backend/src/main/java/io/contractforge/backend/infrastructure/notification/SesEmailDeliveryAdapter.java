package io.contractforge.backend.infrastructure.notification;

import io.contractforge.backend.application.notification.EmailDeliveryException;
import io.contractforge.backend.domain.model.NotificationFailureCode;
import io.contractforge.backend.application.notification.EmailDeliveryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SesException;

/** SES transport adapter; it owns no notification meaning or templates. */
@Component
@Profile("!test")
final class SesEmailDeliveryAdapter implements EmailDeliveryPort {
    private final SesClient ses;
    private final NotificationDeliverySenderProperties properties;

    SesEmailDeliveryAdapter(SesClient ses, NotificationDeliverySenderProperties properties) {
        this.ses = ses;
        this.properties = properties;
    }

    @Override
    public void send(EmailMessage email) {
        if (properties.senderAddress() == null || properties.senderAddress().isBlank()) {
            throw new EmailDeliveryException(false, NotificationFailureCode.EMAIL_SENDER_UNAVAILABLE.name());
        }
        try {
            ses.sendEmail(SendEmailRequest.builder()
                    .source(properties.senderAddress())
                    .destination(Destination.builder().toAddresses(email.destination()).build())
                    .message(Message.builder()
                            .subject(Content.builder().data(email.subject()).charset("UTF-8").build())
                            .body(Body.builder().text(Content.builder().data(email.body()).charset("UTF-8").build()).build())
                            .build())
                    .build());
        } catch (SesException failure) {
            String errorCode = failure.awsErrorDetails() == null ? null : failure.awsErrorDetails().errorCode();
            boolean retryable = errorCode != null && (errorCode.contains("Throttl") || errorCode.contains("Limit"))
                    || failure.statusCode() >= 500;
            throw new EmailDeliveryException(retryable,
                    retryable ? NotificationFailureCode.EMAIL_PROVIDER_UNAVAILABLE.name()
                            : NotificationFailureCode.EMAIL_REJECTED.name());
        } catch (RuntimeException failure) {
            throw new EmailDeliveryException(true, NotificationFailureCode.EMAIL_PROVIDER_UNAVAILABLE.name());
        }
    }
}
