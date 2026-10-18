package io.contractforge.backend.application.monitoring;

import io.contractforge.backend.domain.model.RecommendationType;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.domain.model.PersistenceModels.Event;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractRecommendationTests {
    private static final ContractRecommendationService SERVICE = new ContractRecommendationService(new ObjectMapper());

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID DOCUMENT_ID = UUID.randomUUID();
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-16T02:00:00Z");

    @Test
    void expiryEventProducesStructuredPresentationNeutralRecommendation() {
        var recommendation = SERVICE.from(event("CONTRACT_EXPIRING",
                "contract-term:CONTRACT_EXPIRING:2026-09-16")).orElseThrow();

        assertThat(recommendation.type()).isEqualTo(RecommendationType.CONTRACT_EXPIRY);
        assertThat(recommendation.userId()).isEqualTo(USER_ID);
        assertThat(recommendation.documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(recommendation.data().value())
                .contains("\"reason\":{\"type\":\"CONTRACT_EXPIRY\"}")
                .contains("\"event_type\":\"CONTRACT_EXPIRING\"")
                .contains("\"impact\":{\"type\":\"NONE\"}")
                .contains("\"action\":{\"type\":\"REVIEW\"}")
                .doesNotContain("Electricity contract");
    }

    @Test
    void renewalEventProducesRenewalRecommendationWithDeterministicIdentity() {
        Event occurrence = event("CONTRACT_RENEWING", "contract-term:CONTRACT_RENEWING:2026-09-16");

        var first = SERVICE.from(occurrence).orElseThrow();
        var second = SERVICE.from(occurrence).orElseThrow();
        var later = SERVICE.from(
                event("CONTRACT_RENEWING", "contract-term:CONTRACT_RENEWING:2027-09-16")).orElseThrow();

        assertThat(first.type()).isEqualTo(RecommendationType.RENEWAL);
        assertThat(first.id()).isEqualTo(second.id());
        assertThat(later.id()).isNotEqualTo(first.id());
    }

    @Test
    void unsupportedEventDoesNotProduceRecommendation() {
        assertThat(SERVICE.from(event("PAYMENT_DUE", "payment:2026-09-16"))).isEmpty();
    }

    @Test
    void incompleteSupportedEventDoesNotProduceRecommendation() {
        Event incomplete = new Event(UUID.randomUUID(), USER_ID, DOCUMENT_ID, "CONTRACT_EXPIRING",
                "not used", CREATED_AT, null, null, "ACTIVE", null, CREATED_AT);

        assertThat(SERVICE.from(incomplete)).isEmpty();
    }

    private static Event event(String type, String sourceReference) {
        return new Event(UUID.nameUUIDFromBytes(sourceReference.getBytes()), USER_ID, DOCUMENT_ID, type,
                "not used", CREATED_AT, null, null, "ACTIVE", sourceReference, CREATED_AT);
    }
}
