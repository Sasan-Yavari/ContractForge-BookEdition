package io.contractforge.backend;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.contractforge.backend.api.security.SecurityTestConfiguration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "contractforge.security.cognito.issuer-uri=https://cognito.example.test",
        "contractforge.security.cognito.client-id=client-123"
})
@ActiveProfiles({"integration", "security-test"})
@Import(SecurityTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MonitoringResultHttpIntegrationTests extends PostgresIntegrationTest {

    private static final UUID OWNER_DOCUMENT = UUID.fromString("72000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_DOCUMENT = UUID.fromString("72000000-0000-0000-0000-000000000002");
    private static final UUID OWNER_EVENT_EARLIER = UUID.fromString("72000000-0000-0000-0000-000000000011");
    private static final UUID OWNER_EVENT_LATER = UUID.fromString("72000000-0000-0000-0000-000000000012");
    private static final UUID OTHER_EVENT = UUID.fromString("72000000-0000-0000-0000-000000000013");
    private static final UUID OWNER_RECOMMENDATION_CURRENT = UUID.fromString("72000000-0000-0000-0000-000000000022");
    private static final UUID OWNER_RECOMMENDATION_EXPIRED = UUID.fromString("72000000-0000-0000-0000-000000000021");
    private static final UUID OTHER_RECOMMENDATION = UUID.fromString("72000000-0000-0000-0000-000000000023");

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM documents WHERE id IN (?, ?)", OWNER_DOCUMENT, OTHER_DOCUMENT);

        mockMvc.perform(get("/v1/events").header("Authorization", "Bearer access-owner"));
        mockMvc.perform(get("/v1/events").header("Authorization", "Bearer access-other"));
        UUID owner = userId("owner");
        UUID other = userId("other");
        OffsetDateTime now = OffsetDateTime.now();
        insertDocument(OWNER_DOCUMENT, owner, now);
        insertDocument(OTHER_DOCUMENT, other, now);

        OffsetDateTime sameEventDate = now.minusDays(1);
        insertEvent(OWNER_EVENT_EARLIER, owner, OWNER_DOCUMENT, sameEventDate, "CONTRACT_EXPIRING");
        insertEvent(OWNER_EVENT_LATER, owner, OWNER_DOCUMENT, sameEventDate, "CONTRACT_RENEWING");
        insertEvent(OTHER_EVENT, other, OTHER_DOCUMENT, sameEventDate, "CONTRACT_EXPIRING");
        insertRecommendation(OWNER_RECOMMENDATION_CURRENT, owner, OWNER_DOCUMENT, now, null);
        insertRecommendation(OWNER_RECOMMENDATION_EXPIRED, owner, OWNER_DOCUMENT, now, now.minusDays(1));
        insertRecommendation(OTHER_RECOMMENDATION, other, OTHER_DOCUMENT, now, null);
    }

    @Test
    void ownerReadsOnlyTheirEventsAndCurrentRecommendationsInStableOrder() throws Exception {
        mockMvc.perform(get("/v1/events").header("Authorization", "Bearer access-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(OWNER_EVENT_LATER.toString()))
                .andExpect(jsonPath("$[1].id").value(OWNER_EVENT_EARLIER.toString()))
                .andExpect(jsonPath("$[0].documentId").value(OWNER_DOCUMENT.toString()))
                .andExpect(jsonPath("$[0].type").value("CONTRACT_RENEWING"));

        mockMvc.perform(get("/v1/recommendations").header("Authorization", "Bearer access-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(OWNER_RECOMMENDATION_CURRENT.toString()))
                .andExpect(jsonPath("$[0].data.reason.type").value("CONTRACT_EXPIRY"))
                .andExpect(jsonPath("$[0].data.impact.type").value("NONE"))
                .andExpect(jsonPath("$[0].data.evidence.source_reference").doesNotExist());
    }

    @Test
    void explicitHistoryIncludesExpiredOwnerRecommendationWithoutOtherUsersData() throws Exception {
        int countBefore = recommendationCount(OWNER_RECOMMENDATION_EXPIRED);
        OffsetDateTime expiresAtBefore = recommendationExpiry(OWNER_RECOMMENDATION_EXPIRED);
        mockMvc.perform(get("/v1/recommendations").param("includeHistory", "true")
                        .header("Authorization", "Bearer access-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(OWNER_RECOMMENDATION_CURRENT.toString()))
                .andExpect(jsonPath("$[1].id").value(OWNER_RECOMMENDATION_EXPIRED.toString()))
                .andExpect(jsonPath("$[1].expiresAt").exists())
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem(OTHER_RECOMMENDATION.toString()))));

        mockMvc.perform(get("/v1/events").header("Authorization", "Bearer access-other"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(OTHER_EVENT.toString()));
        org.assertj.core.api.Assertions.assertThat(recommendationCount(OWNER_RECOMMENDATION_EXPIRED)).isEqualTo(countBefore);
        org.assertj.core.api.Assertions.assertThat(recommendationExpiry(OWNER_RECOMMENDATION_EXPIRED))
                .isEqualTo(expiresAtBefore);
    }

    @Test
    void unauthenticatedMonitoringReadsFailClosed() throws Exception {
        mockMvc.perform(get("/v1/events")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/recommendations")).andExpect(status().isUnauthorized());
    }

    private void insertDocument(UUID documentId, UUID userId, OffsetDateTime now) {
        jdbc.update("INSERT INTO documents (id, user_id, storage_key, filename, mime_type, size, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'monitoring.pdf', 'application/pdf', 42, 'SUCCESS', ?, ?)",
                documentId, userId, "trusted/" + documentId, now, now);
    }

    private void insertEvent(UUID id, UUID userId, UUID documentId, OffsetDateTime eventDate, String type) {
        jdbc.update("INSERT INTO events (id, user_id, document_id, type, description, event_date, status, created_at) "
                        + "VALUES (?, ?, ?, ?, 'Monitoring event', ?, 'ACTIVE', ?)",
                id, userId, documentId, type, eventDate, eventDate);
    }

    private void insertRecommendation(UUID id, UUID userId, UUID documentId, OffsetDateTime createdAt,
                                      OffsetDateTime expiresAt) {
        jdbc.update("INSERT INTO recommendations (id, user_id, document_id, type, data, created_at, expires_at) "
                        + "VALUES (?, ?, ?, 'CONTRACT_EXPIRY', ?::jsonb, ?, ?)", id, userId, documentId,
                "{\"reason\":{\"type\":\"CONTRACT_EXPIRY\"},\"evidence\":{\"event_type\":\"CONTRACT_EXPIRING\","
                        + "\"event_date\":\"2026-01-01T00:00:00Z\"},\"impact\":{\"type\":\"NONE\"},"
                        + "\"action\":{\"type\":\"REVIEW\"}}",
                createdAt, expiresAt);
    }

    private UUID userId(String providerSubject) {
        return jdbc.queryForObject("SELECT user_id FROM user_identities WHERE provider = 'cognito' AND provider_subject = ?",
                UUID.class, providerSubject);
    }

    private int recommendationCount(UUID recommendationId) {
        return jdbc.queryForObject("SELECT count(*) FROM recommendations WHERE id = ?", Integer.class, recommendationId);
    }

    private OffsetDateTime recommendationExpiry(UUID recommendationId) {
        return jdbc.queryForObject("SELECT expires_at FROM recommendations WHERE id = ?", OffsetDateTime.class,
                recommendationId);
    }
}
