package io.contractforge.backend.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.contractforge.backend.application.notification.RecommendationNotificationView.ActionType;
import io.contractforge.backend.application.notification.RecommendationNotificationView.ImpactType;
import io.contractforge.backend.domain.model.MonitoringEventType;
import io.contractforge.backend.domain.model.RecommendationType;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class RepositoryEmailRendererTests {
    private static final UUID RECOMMENDATION_ID = UUID.fromString("81000000-0000-0000-0000-000000000001");
    private final RepositoryEmailRenderer renderer = new RepositoryEmailRenderer();

    @Test
    void rendersContractExpiryRecommendationDeterministically() {
        RecommendationNotificationView view = view(RecommendationType.CONTRACT_EXPIRY, MonitoringEventType.CONTRACT_EXPIRING);

        EmailRenderer.RenderedEmail first = renderer.render(view);
        EmailRenderer.RenderedEmail second = renderer.render(view);

        assertThat(first).isEqualTo(second);
        assertThat(first.subject()).isEqualTo("ContractForge: review your contract expiry recommendation");
        assertThat(first.body()).contains("contract expiry event on 2026-01-02T00:00:00Z");
        assertThat(first.body()).contains("Suggested action: review.");
    }

    @Test
    void rendersRenewalRecommendationWithOnlyApprovedSemanticFacts() {
        EmailRenderer.RenderedEmail email = renderer.render(
                view(RecommendationType.RENEWAL, MonitoringEventType.CONTRACT_RENEWING));

        assertThat(email.subject()).isEqualTo("ContractForge: review your renewal recommendation");
        assertThat(email.body()).contains("renewal event on 2026-01-02T00:00:00Z");
        assertThat(email.body()).doesNotContain(RECOMMENDATION_ID.toString());
        assertThat(email.body()).doesNotContain("document").doesNotContain("prompt")
                .doesNotContain("storage").doesNotContain("secret");
    }

    @Test
    void rendersDeterministicallyWithTurkishDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(renderer.render(view(RecommendationType.RENEWAL, MonitoringEventType.CONTRACT_RENEWING)).body())
                    .contains("Suggested action: review.");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void rejectsUnknownSchemaVersionBeforeRendering() {
        assertThatThrownBy(() -> new RecommendationNotificationView(2, RECOMMENDATION_ID,
                RecommendationType.CONTRACT_EXPIRY, MonitoringEventType.CONTRACT_EXPIRING,
                OffsetDateTime.parse("2026-01-02T00:00:00Z"), ImpactType.NONE, ActionType.REVIEW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedSemanticValuesAndMismatchedFacts() {
        assertThatThrownBy(() -> new RecommendationNotificationView(RecommendationNotificationView.CURRENT_SCHEMA_VERSION, RECOMMENDATION_ID,
                RecommendationType.CONTRACT_EXPIRY, MonitoringEventType.CONTRACT_RENEWING,
                OffsetDateTime.parse("2026-01-02T00:00:00Z"), ImpactType.NONE, ActionType.REVIEW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecommendationNotificationView(RecommendationNotificationView.CURRENT_SCHEMA_VERSION, RECOMMENDATION_ID,
                RecommendationType.CONTRACT_EXPIRY, MonitoringEventType.CONTRACT_EXPIRING,
                OffsetDateTime.parse("2026-01-02T01:00:00+01:00"), ImpactType.NONE, ActionType.REVIEW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RecommendationNotificationView view(RecommendationType type, MonitoringEventType eventType) {
        return new RecommendationNotificationView(RecommendationNotificationView.CURRENT_SCHEMA_VERSION, RECOMMENDATION_ID, type, eventType,
                OffsetDateTime.parse("2026-01-02T00:00:00Z"), ImpactType.NONE, ActionType.REVIEW);
    }
}
