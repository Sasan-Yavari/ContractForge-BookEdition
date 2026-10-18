package io.contractforge.backend.application.notification;

/** Application boundary for rendering repository-owned email content. */
public interface EmailRenderer {
    RenderedEmail render(RecommendationNotificationView view);

    record RenderedEmail(String subject, String body) {
        public RenderedEmail {
            if (subject == null || subject.isBlank() || body == null || body.isBlank()) {
                throw new IllegalArgumentException("Rendered email content must not be blank");
            }
        }
    }
}
