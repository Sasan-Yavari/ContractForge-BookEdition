package io.contractforge.backend.application.notification;

import io.contractforge.backend.application.notification.EmailRenderer.RenderedEmail;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Renders the versioned MVP email templates owned by this repository. */
@Component
@Profile("!test")
public final class RepositoryEmailRenderer implements EmailRenderer {
    private static final String SUBJECT_TEMPLATE = "email/notification/recommendation-v1/subject.txt";
    private static final String BODY_TEMPLATE = "email/notification/recommendation-v1/body.txt";

    private final String subjectTemplate;
    private final String bodyTemplate;

    public RepositoryEmailRenderer() {
        subjectTemplate = load(SUBJECT_TEMPLATE);
        bodyTemplate = load(BODY_TEMPLATE);
    }

    @Override
    public RenderedEmail render(RecommendationNotificationView view) {
        if (view == null) {
            throw new IllegalArgumentException("Recommendation notification view is required");
        }
        Map<String, String> values = Map.of(
                "recommendationType", view.recommendationType().displayName(),
                "eventDate", view.eventDateRfc3339(),
                "action", view.actionType().name().toLowerCase(Locale.ROOT));
        return new RenderedEmail(apply(subjectTemplate, values), apply(bodyTemplate, values));
    }

    private String apply(String template, Map<String, String> values) {
        String rendered = template;
        for (Map.Entry<String, String> value : values.entrySet()) {
            rendered = rendered.replace("{{" + value.getKey() + "}}", value.getValue());
        }
        if (rendered.contains("{{")) {
            throw new IllegalStateException("Repository email template contains an unknown placeholder");
        }
        return rendered.strip();
    }

    private String load(String path) {
        try (InputStream stream = new ClassPathResource(path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Repository email template is unavailable: " + path, exception);
        }
    }
}
