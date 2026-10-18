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
class ContractCollectionHttpIntegrationTests extends PostgresIntegrationTest {
    private static final UUID UPLOADING = id("73000000-0000-0000-0000-000000000001");
    private static final UUID PROCESSING = id("73000000-0000-0000-0000-000000000002");
    private static final UUID FAILED = id("73000000-0000-0000-0000-000000000003");
    private static final UUID DRAFT = id("73000000-0000-0000-0000-000000000004");
    private static final UUID CONFIRMED = id("73000000-0000-0000-0000-000000000005");
    private static final UUID REJECTED = id("73000000-0000-0000-0000-000000000006");
    private static final UUID DELETED_UPLOAD = id("73000000-0000-0000-0000-000000000007");
    private static final UUID OTHER_DOCUMENT = id("73000000-0000-0000-0000-000000000008");
    private static final UUID EXPIRED_UPLOAD = id("73000000-0000-0000-0000-000000000009");
    private static final UUID ATTENTION = id("73000000-0000-0000-0000-000000000010");
    private static final UUID ATTENTION_RECOMMENDATION = id("73000000-0000-0000-0000-000000000011");
    private static final UUID ARCHIVED = id("73000000-0000-0000-0000-000000000012");

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    private MockMvc mockMvc;
    private UUID owner;
    private UUID other;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM documents WHERE id IN (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UPLOADING, PROCESSING, FAILED, DRAFT, CONFIRMED, REJECTED, DELETED_UPLOAD, OTHER_DOCUMENT,
                EXPIRED_UPLOAD, ATTENTION, ARCHIVED);
        jdbc.update("DELETE FROM uploads WHERE id IN (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UPLOADING, PROCESSING, FAILED, DRAFT, CONFIRMED, REJECTED, DELETED_UPLOAD, OTHER_DOCUMENT,
                EXPIRED_UPLOAD, ATTENTION, ARCHIVED);

        ensureIdentity("owner");
        ensureIdentity("other");
        owner = userId("owner");
        other = userId("other");
        OffsetDateTime now = OffsetDateTime.now().withNano(0);

        insertUpload(UPLOADING, owner, "UPLOADING", "uploading.pdf", now.minusHours(5), now.plusHours(1));
        insertDocument(PROCESSING, owner, "PROCESSING", now.minusHours(4));
        insertUpload(PROCESSING, owner, "COMPLETED", "processing.pdf", now.minusHours(4), now.plusDays(1));
        insertDocument(FAILED, owner, "FAILED", now.minusHours(2));
        insertUpload(FAILED, owner, "COMPLETED", "failed.pdf", now.minusHours(2), now.plusDays(1));
        insertDocument(DRAFT, owner, "SUCCESS", now.minusHours(1));
        insertUpload(DRAFT, owner, "COMPLETED", "draft.pdf", now.minusHours(1), now.plusDays(1));
        insertContract(DRAFT, "DRAFT", now.minusHours(1));
        insertParty(DRAFT, "Unconfirmed Provider");
        insertDocument(CONFIRMED, owner, "SUCCESS", now.minusHours(3));
        insertUpload(CONFIRMED, owner, "COMPLETED", "confirmed.pdf", now.minusHours(3), now.plusDays(1));
        insertContract(CONFIRMED, "ACTIVE", now.minusHours(3));
        insertParty(CONFIRMED, "Trusted Provider");
        jdbc.update("INSERT INTO contract_terms (contract_id, end_date, pricing_model) VALUES (?, '2027-05-01', 'FIXED')",
                CONFIRMED);
        insertUpload(REJECTED, owner, "REJECTED", "rejected.pdf", now.minusHours(90), now.plusDays(1));
        insertUpload(DELETED_UPLOAD, owner, "COMPLETED", "deleted.pdf", now.minusMinutes(1), now.plusDays(1));
        insertUpload(EXPIRED_UPLOAD, owner, "UPLOADING", "expired.pdf", now.minusDays(2), now.minusDays(1));
        insertDocument(ATTENTION, owner, "SUCCESS", now.minusDays(1));
        insertUpload(ATTENTION, owner, "COMPLETED", "attention.pdf", now.minusDays(1), now.plusDays(1));
        insertContract(ATTENTION, "ACTIVE", now.minusDays(1));
        insertParty(ATTENTION, "Attention Provider");
        insertDocument(ARCHIVED, owner, "SUCCESS", now.minusDays(5));
        insertContract(ARCHIVED, "ARCHIVED", now.minusDays(5));
        insertParty(ARCHIVED, "Archived Provider");
        jdbc.update("INSERT INTO recommendations (id, user_id, document_id, type, data, created_at, expires_at) "
                        + "VALUES (?, ?, ?, 'CONTRACT_EXPIRY', '{}'::jsonb, ?, ?)",
                ATTENTION_RECOMMENDATION, owner, ATTENTION, now, now.plusDays(1));
        insertDocument(OTHER_DOCUMENT, other, "SUCCESS", now);
        insertUpload(OTHER_DOCUMENT, other, "COMPLETED", "other.pdf", now, now.plusDays(1));
        insertContract(OTHER_DOCUMENT, "ACTIVE", now);
        insertParty(OTHER_DOCUMENT, "Other Provider");
    }

    @Test
    void ownerGetsAllLifecycleStatesInStablePriorityOrderAndOnlyTrustedDetails() throws Exception {
        mockMvc.perform(get("/v1/contracts").header("Authorization", "Bearer access-owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$[0].id").value(DRAFT.toString()))
                .andExpect(jsonPath("$[0].contractType").value("ELECTRICITY"))
                .andExpect(jsonPath("$[0].status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$[0].sourceFilename").value("draft.pdf"))
                .andExpect(jsonPath("$[0].provider").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(FAILED.toString()))
                .andExpect(jsonPath("$[1].status").value("COULD_NOT_PROCESS"))
                .andExpect(jsonPath("$[2].id").value(EXPIRED_UPLOAD.toString()))
                .andExpect(jsonPath("$[2].status").value("COULD_NOT_PROCESS"))
                .andExpect(jsonPath("$[3].id").value(REJECTED.toString()))
                .andExpect(jsonPath("$[3].status").value("COULD_NOT_PROCESS"))
                .andExpect(jsonPath("$[4].id").value(ATTENTION.toString()))
                .andExpect(jsonPath("$[4].status").value("NEEDS_ATTENTION"))
                .andExpect(jsonPath("$[4].sourceFilename").doesNotExist())
                .andExpect(jsonPath("$[4].provider").value("Attention Provider"))
                .andExpect(jsonPath("$[5].id").value(CONFIRMED.toString()))
                .andExpect(jsonPath("$[5].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[5].sourceFilename").doesNotExist())
                .andExpect(jsonPath("$[5].provider").value("Trusted Provider"))
                .andExpect(jsonPath("$[5].pricingModel").value("FIXED"))
                .andExpect(jsonPath("$[5].endDate").value("2027-05-01"))
                .andExpect(jsonPath("$[6].id").value(ARCHIVED.toString()))
                .andExpect(jsonPath("$[6].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[6].provider").value("Archived Provider"))
                .andExpect(jsonPath("$[7].id").value(PROCESSING.toString()))
                .andExpect(jsonPath("$[7].status").value("PROCESSING"))
                .andExpect(jsonPath("$[8].id").value(UPLOADING.toString()))
                .andExpect(jsonPath("$[8].status").value("PROCESSING"))
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(
                        OTHER_DOCUMENT.toString()))))
                .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(
                        DELETED_UPLOAD.toString()))));
    }

    @Test
    void emptyCollectionIsAnEmptyArrayAndUnauthenticatedReadsAreRejected() throws Exception {
        mockMvc.perform(get("/v1/contracts").header("Authorization", "Bearer access-empty"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/v1/contracts")).andExpect(status().isUnauthorized());
    }

    private void ensureIdentity(String subject) throws Exception {
        mockMvc.perform(get("/v1/contracts").header("Authorization", "Bearer access-" + subject));
    }

    private UUID userId(String subject) {
        return jdbc.queryForObject("SELECT user_id FROM user_identities WHERE provider = 'cognito' AND provider_subject = ?",
                UUID.class, subject);
    }

    private void insertDocument(UUID id, UUID userId, String status, OffsetDateTime createdAt) {
        jdbc.update("INSERT INTO documents (id, user_id, storage_key, filename, mime_type, size, contract_type, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'application/pdf', 42, 'ELECTRICITY', ?, ?, ?)",
                id, userId, "trusted/" + id, id + ".pdf", status, createdAt, createdAt);
    }

    private void insertUpload(UUID id, UUID userId, String status, String filename,
                              OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        jdbc.update("INSERT INTO uploads (id, user_id, status, object_key, original_filename, contract_type, expires_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'ELECTRICITY', ?, ?)",
                id, userId, status, "quarantine/" + id, filename, expiresAt, createdAt);
    }

    private void insertContract(UUID documentId, String status, OffsetDateTime createdAt) {
        jdbc.update("INSERT INTO contracts (document_id, type, status, data, version, created_at, updated_at) "
                        + "VALUES (?, 'ELECTRICITY', ?, '{}'::jsonb, 0, ?, ?)",
                documentId, status, createdAt, createdAt);
    }

    private void insertParty(UUID documentId, String provider) {
        jdbc.update("INSERT INTO contract_parties (id, contract_id, role, name) VALUES (?, ?, 'PROVIDER', ?)",
                UUID.randomUUID(), documentId, provider);
    }

    private static UUID id(String value) {
        return UUID.fromString(value);
    }
}
