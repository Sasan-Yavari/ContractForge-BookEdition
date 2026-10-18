package io.contractforge.backend.application.notification;

/** Provider-neutral application boundary for sending one rendered email. */
public interface EmailDeliveryPort {
    void send(EmailMessage message);

    record EmailMessage(String destination, String subject, String body) {
        public EmailMessage {
            if (destination == null || destination.isBlank() || subject == null || subject.isBlank()
                    || body == null || body.isBlank()) {
                throw new IllegalArgumentException("Email message is incomplete");
            }
        }
    }
}
