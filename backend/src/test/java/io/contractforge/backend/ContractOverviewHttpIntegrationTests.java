package io.contractforge.backend;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.contractforge.backend.api.security.SecurityTestConfiguration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
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
class ContractOverviewHttpIntegrationTests extends PostgresIntegrationTest {
    private static final UUID OWNER_CONTRACT = id("74000000-0000-0000-0000-000000000001");
    private static final UUID OWNER_OTHER_CONTRACT = id("74000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_CONTRACT = id("74000000-0000-0000-0000-000000000003");
    private static final UUID ACTIVE_ATTENTION_EVENT = id("74000000-0000-0000-0000-000000000011");
    private static final UUID ACTIVE_THINGS_EVENT = id("74000000-0000-0000-0000-000000000012");
    private static final UUID OLD_EVENT = id("74000000-0000-0000-0000-000000000013");
    private static final UUID OTHER_CONTRACT_EVENT = id("74000000-0000-0000-0000-000000000014");
    private static final UUID OTHER_OWNER_EVENT = id("74000000-0000-0000-0000-000000000015");
    private static final UUID NEWER_HISTORY_EVENT = id("74000000-0000-0000-0000-000000000017");
    private static final UUID CURRENT_RECOMMENDATION = id("74000000-0000-0000-0000-000000000021");
    private static final UUID EXPIRED_RECOMMENDATION = id("74000000-0000-0000-0000-000000000022");

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    private MockMvc mockMvc;
    private UUID owner;
    private UUID other;
    private String oldEventDate;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM documents WHERE id IN (?, ?, ?)", OWNER_CONTRACT, OWNER_OTHER_CONTRACT, OTHER_CONTRACT);
        ensureIdentity("owner");
        ensureIdentity("other");
        owner = userId("owner");
        other = userId("other");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        oldEventDate = now.minusDays(3).toLocalDate().toString();
        insertActiveContract(OWNER_CONTRACT, owner, now.minusDays(4));
        insertActiveContract(OWNER_OTHER_CONTRACT, owner, now.minusDays(3));
        insertActiveContract(OTHER_CONTRACT, other, now.minusDays(2));

        insertEvent(ACTIVE_ATTENTION_EVENT, owner, OWNER_CONTRACT, "CONTRACT_EXPIRING", "ACTIVE",
                "expires-2027", now.plusDays(30));
        insertEvent(ACTIVE_THINGS_EVENT, owner, OWNER_CONTRACT, "CONTRACT_RENEWING", "ACTIVE",
                "renewal-2027", now.plusDays(45));
        insertEvent(OLD_EVENT, owner, OWNER_CONTRACT, "CONTRACT_EXPIRING", "COMPLETED",
                "expires-2026", now.minusDays(3));
        insertEvent(NEWER_HISTORY_EVENT, owner, OWNER_CONTRACT, "REQUIREMENT", "DISMISSED",
                "notice-2026", now.minusDays(2));
        insertEvent(OTHER_CONTRACT_EVENT, owner, OWNER_OTHER_CONTRACT, "CONTRACT_RENEWING", "ACTIVE",
                "another-contract", now.plusDays(20));
        insertEvent(OTHER_OWNER_EVENT, other, OWNER_CONTRACT, "CONTRACT_RENEWING", "ACTIVE",
                "mismatched-owner", now.plusDays(10));
        insertEvent(id("74000000-0000-0000-0000-000000000016"), other, OTHER_CONTRACT,
                "CONTRACT_EXPIRING", "ACTIVE", "other-owner-contract", now.plusDays(10));

        insertRecommendation(CURRENT_RECOMMENDATION, owner, OWNER_CONTRACT, "CONTRACT_EXPIRY",
                "CONTRACT_EXPIRING", "expires-2027", now.minusDays(1), null);
        insertRecommendation(EXPIRED_RECOMMENDATION, owner, OWNER_CONTRACT, "CONTRACT_EXPIRY",
                "CONTRACT_EXPIRING", "expires-2026", now.minusDays(5), now.minusDays(1));
    }

    @Test
    void confirmedOwnerReadsContractSpecificCurrentCategoriesAndUnifiedHistory() throws Exception {
        mockMvc.perform(get("/v1/contracts/{documentId}/overview", OWNER_CONTRACT)
                        .header("Authorization", "Bearer access-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(OWNER_CONTRACT.toString()))
                .andExpect(jsonPath("$.needsAttention.length()").value(1))
                .andExpect(jsonPath("$.needsAttention[0].id").value(CURRENT_RECOMMENDATION.toString()))
                .andExpect(jsonPath("$.thingsToKnow.length()").value(1))
                .andExpect(jsonPath("$.thingsToKnow[0].id").value(ACTIVE_THINGS_EVENT.toString()))
                .andExpect(jsonPath("$.thingsToKnow[0].documentId").value(OWNER_CONTRACT.toString()))
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[0].id").value(NEWER_HISTORY_EVENT.toString()))
                .andExpect(jsonPath("$.history[0].event.id").value(NEWER_HISTORY_EVENT.toString()))
                .andExpect(jsonPath("$.history[1].id").value(OLD_EVENT.toString()))
                .andExpect(jsonPath("$.history[1].event.id").value(OLD_EVENT.toString()))
                .andExpect(jsonPath("$.history[1].recommendation.id").value(EXPIRED_RECOMMENDATION.toString()))
                .andExpect(jsonPath("$.history[1].occurredAt").value(
                        org.hamcrest.Matchers.startsWith(oldEventDate)))
                .andExpect(jsonPath("$.history[1].event.sourceReference").doesNotExist());
    }

    @Test
    void contractScopeAndConfirmedOwnershipAreNonDisclosing() throws Exception {
        mockMvc.perform(get("/v1/contracts/{documentId}/overview", OWNER_CONTRACT)
                        .header("Authorization", "Bearer access-other"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/contracts/{documentId}/overview", OTHER_CONTRACT)
                        .header("Authorization", "Bearer access-owner"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/contracts/{documentId}/overview", OWNER_OTHER_CONTRACT)
                        .header("Authorization", "Bearer access-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thingsToKnow.length()").value(1))
                .andExpect(jsonPath("$.thingsToKnow[0].id").value(OTHER_CONTRACT_EVENT.toString()))
                .andExpect(jsonPath("$.needsAttention.length()").value(0))
                .andExpect(jsonPath("$.history.length()").value(0));
        mockMvc.perform(get("/v1/contracts/{documentId}/overview", OWNER_CONTRACT))
                .andExpect(status().isUnauthorized());
    }

    private void ensureIdentity(String subject) throws Exception {
        mockMvc.perform(get("/v1/contracts").header("Authorization", "Bearer access-" + subject));
    }

    private UUID userId(String subject) {
        return jdbc.queryForObject("SELECT user_id FROM user_identities WHERE provider = 'cognito' AND provider_subject = ?",
                UUID.class, subject);
    }

    private void insertActiveContract(UUID id, UUID userId, OffsetDateTime createdAt) {
        jdbc.update("INSERT INTO documents (id, user_id, storage_key, filename, mime_type, size, contract_type, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'contract.pdf', 'application/pdf', 42, 'ELECTRICITY', 'SUCCESS', ?, ?)",
                id, userId, "trusted/" + id, createdAt, createdAt);
        jdbc.update("INSERT INTO contracts (document_id, type, status, data, version, created_at, updated_at) "
                        + "VALUES (?, 'ELECTRICITY', 'ACTIVE', '{}'::jsonb, 0, ?, ?)", id, createdAt, createdAt);
    }

    private void insertEvent(UUID id, UUID userId, UUID documentId, String type, String status,
                             String sourceReference, OffsetDateTime eventDate) {
        jdbc.update("INSERT INTO events (id, user_id, document_id, type, description, event_date, status, source_reference, created_at) "
                        + "VALUES (?, ?, ?, ?, 'Contract information', ?, ?, ?, ?)",
                id, userId, documentId, type, eventDate, status, sourceReference, eventDate.minusDays(1));
    }

    private void insertRecommendation(UUID id, UUID userId, UUID documentId, String type,
                                      String eventType, String sourceReference,
                                      OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        String data = "{\"reason\":{\"type\":\"" + type + "\"},"
                + "\"evidence\":{\"event_type\":\"" + eventType + "\","
                + "\"event_date\":\"2027-01-01T00:00:00Z\",\"source_reference\":\""
                + sourceReference + "\"},\"impact\":{\"type\":\"NONE\"},"
                + "\"action\":{\"type\":\"REVIEW\"}}";
        jdbc.update("INSERT INTO recommendations (id, user_id, document_id, type, data, created_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)",
                id, userId, documentId, type, data, createdAt, expiresAt);
    }

    private static UUID id(String value) {
        return UUID.fromString(value);
    }
}
