package io.contractforge.backend;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.contractforge.backend.api.security.SecurityTestConfiguration;
import io.contractforge.backend.application.extraction.ContractExtractionPort;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.context.annotation.Import;
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
class ContractReviewHttpIntegrationTests extends PostgresIntegrationTest {
    private static final UUID DOCUMENT = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID PARTY = UUID.fromString("70000000-0000-0000-0000-000000000002");
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM documents WHERE id = ?", DOCUMENT);
        // Resolve the owner through the same authenticated API boundary used by production requests.
        mockMvc.perform(get("/v1/contracts/{id}", DOCUMENT).header("Authorization", "Bearer access-owner"));
        UUID user = jdbc.queryForObject("SELECT user_id FROM user_identities WHERE provider_subject = 'owner'", UUID.class);
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO documents (id, user_id, storage_key, filename, mime_type, size, status, created_at, updated_at) "
                + "VALUES (?, ?, 'trusted/document', 'contract.pdf', 'application/pdf', 42, 'SUCCESS', ?, ?)", DOCUMENT, user, now, now);
        jdbc.update("INSERT INTO contracts (document_id, type, status, data, version, created_at, updated_at) "
                + "VALUES (?, 'ELECTRICITY', 'DRAFT', '{\"schema_version\":\""
                + ContractExtractionPort.CURRENT_SCHEMA_VERSION + "\"}', 0, ?, ?)", DOCUMENT, now, now);
        jdbc.update("INSERT INTO contract_parties (id, contract_id, role, name) VALUES (?, ?, 'CUSTOMER', 'Ada')", PARTY, DOCUMENT);
        jdbc.update("INSERT INTO contract_terms (contract_id, start_date, end_date, contract_duration, renewal_type, pricing_model, electricity_data) "
                + "VALUES (?, '2026-01-01', '2027-01-01', 12, 'FIXED_TERM', 'FIXED', '{\"consumption\":{\"annual_kwh\":100},\"pricing\":{\"model\":\"FIXED\"}}')", DOCUMENT);
    }

    @Test
    void ownerCanReadEditAndExplicitlyApproveTheSameContract() throws Exception {
        mockMvc.perform(get("/v1/contracts/{id}", DOCUMENT).header("Authorization", "Bearer access-owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(0));
        String edit = "{\"expectedVersion\":0,\"parties\":[{\"role\":\"CUSTOMER\",\"name\":\"Grace\"}],"
                + "\"terms\":{\"startDate\":\"2026-01-01\",\"endDate\":\"2027-01-01\",\"durationMonths\":12,"
                + "\"renewalType\":\"FIXED_TERM\",\"pricingModel\":\"FIXED\",\"electricityData\":{\"consumption\":{\"annual_kwh\":200},\"pricing\":{\"model\":\"FIXED\"}}}}";
        mockMvc.perform(put("/v1/contracts/{id}", DOCUMENT).header("Authorization", "Bearer access-owner")
                        .contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(patch("/v1/contracts/{id}", DOCUMENT).header("Authorization", "Bearer access-owner")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(2));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contracts WHERE document_id = ?", Integer.class, DOCUMENT)).isEqualTo(1);
    }

    @Test
    void anotherUserCannotDiscoverTheContractAndStaleApprovalConflicts() throws Exception {
        mockMvc.perform(get("/v1/contracts/{id}", DOCUMENT).header("Authorization", "Bearer access-other"))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/v1/contracts/{id}", DOCUMENT).header("Authorization", "Bearer access-owner")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":99}"))
                .andExpect(status().isConflict());
    }

    @Test
    void ownerCanCorrectActiveContractAndRefreshDerivedMonitoringWithoutLosingTrust() throws Exception {
        mockMvc.perform(patch("/v1/contracts/{id}", DOCUMENT).header("Authorization", "Bearer access-owner")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1));
        UUID owner = jdbc.queryForObject("SELECT user_id FROM documents WHERE id = ?", UUID.class, DOCUMENT);
        String source = "contract-term:CONTRACT_EXPIRING:2026-01-01";
        UUID eventId = UUID.fromString("70000000-0000-0000-0000-000000000010");
        UUID recommendationId = UUID.fromString("70000000-0000-0000-0000-000000000011");
        jdbc.update("INSERT INTO events (id, user_id, document_id, type, description, event_date, status, source_reference, created_at) "
                        + "VALUES (?, ?, ?, 'CONTRACT_EXPIRING', 'old current result', '2026-01-01T00:00:00Z', 'ACTIVE', ?, '2026-01-01T00:00:00Z')",
                eventId, owner, DOCUMENT, source);
        String data = "{\"reason\":{\"type\":\"CONTRACT_EXPIRY\"},\"evidence\":{\"event_type\":\"CONTRACT_EXPIRING\","
                + "\"event_date\":\"2026-01-01T00:00:00Z\",\"source_reference\":\"" + source
                + "\"},\"impact\":{\"type\":\"NONE\"},\"action\":{\"type\":\"REVIEW\"}}";
        jdbc.update("INSERT INTO recommendations (id, user_id, document_id, type, data, created_at) VALUES (?, ?, ?, 'CONTRACT_EXPIRY', ?::jsonb, '2026-01-01T00:00:00Z')",
                recommendationId, owner, DOCUMENT, data);

        String edit = "{\"expectedVersion\":1,\"parties\":[{\"role\":\"CUSTOMER\",\"name\":\"Grace\"}],"
                + "\"terms\":{\"startDate\":\"2000-01-01\",\"endDate\":\"2000-08-01\",\"durationMonths\":7,"
                + "\"renewalType\":\"FIXED_TERM\",\"pricingModel\":\"FIXED\",\"electricityData\":{"
                + "\"consumption\":{\"annual_kwh\":200},\"pricing\":{\"model\":\"FIXED\"}}}}";
        mockMvc.perform(put("/v1/contracts/{id}", DOCUMENT).header("Authorization", "Bearer access-owner")
                        .contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.terms.endDate").value("2000-08-01"))
                .andExpect(jsonPath("$.parties[0].name").value("Grace"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT status FROM contracts WHERE document_id = ?",
                String.class, DOCUMENT)).isEqualTo("ACTIVE");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT status FROM events WHERE id = ?",
                String.class, eventId)).isEqualTo("CANCELLED");
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT expires_at FROM recommendations WHERE id = ?",
                OffsetDateTime.class, recommendationId)).isNotNull();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE document_id = ? "
                + "AND status = 'ACTIVE' AND source_reference = 'contract-term:CONTRACT_EXPIRING:2000-08-01'",
                Integer.class, DOCUMENT)).isEqualTo(1);
        mockMvc.perform(get("/v1/contracts/{id}/overview", DOCUMENT).header("Authorization", "Bearer access-owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.needsAttention.length()").value(1))
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].event.id").value(eventId.toString()))
                .andExpect(jsonPath("$.history[0].recommendation.id").value(recommendationId.toString()));

        mockMvc.perform(put("/v1/contracts/{id}", DOCUMENT).header("Authorization", "Bearer access-owner")
                        .contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isConflict());
        String invalid = "{\"expectedVersion\":2,\"parties\":[],\"terms\":{\"electricityData\":{}}}";
        mockMvc.perform(put("/v1/contracts/{id}", DOCUMENT).header("Authorization", "Bearer access-owner")
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/v1/contracts/{id}", DOCUMENT).header("Authorization", "Bearer access-other")
                        .contentType(MediaType.APPLICATION_JSON).content(edit.replace("\"expectedVersion\":1", "\"expectedVersion\":2")))
                .andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events "
                + "WHERE resource_type = 'CONTRACT' AND resource_id = ? AND action = 'CONFIRMED_EDITED'", Integer.class, DOCUMENT))
                .isEqualTo(1);
    }
}
