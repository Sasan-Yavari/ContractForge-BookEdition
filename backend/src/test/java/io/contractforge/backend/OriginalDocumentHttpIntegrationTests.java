package io.contractforge.backend;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.contractforge.backend.api.security.SecurityTestConfiguration;
import io.contractforge.backend.application.port.ObjectStoragePort;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
@Import({SecurityTestConfiguration.class, OriginalDocumentHttpIntegrationTests.StorageConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OriginalDocumentHttpIntegrationTests extends PostgresIntegrationTest {
    private static final UUID DOCUMENT = UUID.fromString("76000000-0000-0000-0000-000000000001");
    private static final UUID MISSING = UUID.fromString("76000000-0000-0000-0000-000000000002");
    private static final UUID NON_PDF = UUID.fromString("76000000-0000-0000-0000-000000000003");

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Storage storage;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        storage.reset();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM audit_events WHERE resource_id = ?", DOCUMENT);
        jdbc.update("DELETE FROM documents WHERE id IN (?, ?, ?)", DOCUMENT, MISSING, NON_PDF);
        mockMvc.perform(get("/v1/contracts").header("Authorization", "Bearer access-owner"));
        UUID user = jdbc.queryForObject("SELECT user_id FROM user_identities WHERE provider_subject = 'owner'", UUID.class);
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("INSERT INTO documents (id, user_id, storage_key, filename, mime_type, size, status, created_at, updated_at) "
                + "VALUES (?, ?, 'private/trusted/documents/original', 'contract.pdf', 'application/pdf', 42, 'SUCCESS', ?, ?)",
                DOCUMENT, user, now, now);
        jdbc.update("INSERT INTO documents (id, user_id, storage_key, filename, mime_type, size, status, created_at, updated_at) "
                        + "VALUES (?, ?, 'private/trusted/documents/not-pdf', 'notes.txt', 'text/plain', 42, 'SUCCESS', ?, ?)",
                NON_PDF, user, now, now);
    }

    @Test
    void ownerGetsShortLivedInlinePdfTargetAndAccessIsAudited() throws Exception {
        mockMvc.perform(get("/v1/documents/{id}/original", DOCUMENT).header("Authorization", "Bearer access-owner"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.url").value("https://storage.example.test/signed"))
                .andExpect(jsonPath("$.expiresAt").exists());
        org.assertj.core.api.Assertions.assertThat(storage.lastValidity).isEqualTo(Duration.ofMinutes(5));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events "
                + "WHERE resource_id = ? AND action = 'ORIGINAL_ACCESS_ISSUED'", Integer.class, DOCUMENT)).isEqualTo(1);
    }

    @Test
    void unauthorizedAndUnavailableDocumentsAreNonDisclosing() throws Exception {
        mockMvc.perform(get("/v1/documents/{id}/original", DOCUMENT).header("Authorization", "Bearer access-other"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/documents/{id}/original", MISSING).header("Authorization", "Bearer access-owner"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/v1/documents/{id}/original", NON_PDF).header("Authorization", "Bearer access-owner"))
                .andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(storage.requests).isZero();

        storage.missing = true;
        mockMvc.perform(get("/v1/documents/{id}/original", DOCUMENT).header("Authorization", "Bearer access-owner"))
                .andExpect(status().isNotFound());
        storage.missing = false;
        storage.unavailable = true;
        mockMvc.perform(get("/v1/documents/{id}/original", DOCUMENT).header("Authorization", "Bearer access-owner"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ORIGINAL_DOCUMENT_UNAVAILABLE"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events "
                + "WHERE resource_id = ? AND action = 'ORIGINAL_ACCESS_ISSUED'", Integer.class, DOCUMENT)).isZero();
    }

    @Test
    void originalAccessRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/v1/documents/{id}/original", DOCUMENT)).andExpect(status().isUnauthorized());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageConfiguration {
        @Bean @Primary Storage originalDocumentStorage() { return new Storage(); }
    }

    static class Storage implements ObjectStoragePort {
        private int requests;
        private Duration lastValidity;
        private boolean missing;
        private boolean unavailable;

        void reset() { requests = 0; lastValidity = null; missing = false; unavailable = false; }

        @Override public UploadInstructions presignPut(String objectKey, String contentType, long contentLength) {
            throw new UnsupportedOperationException();
        }

        @Override public PresignedRead presignGet(String objectKey, Duration validity) {
            requests++;
            lastValidity = validity;
            if (missing) throw new SourceObjectMissingException();
            if (unavailable) throw new StorageUnavailableException(new IllegalStateException("private storage error"));
            return new PresignedRead(URI.create("https://storage.example.test/signed"));
        }
    }
}
