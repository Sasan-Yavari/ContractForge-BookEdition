package io.contractforge.backend;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.contractforge.backend.api.security.SecurityTestConfiguration;
import io.contractforge.backend.application.port.ObjectStoragePort;
import io.contractforge.backend.application.document.DocumentDeletionQueuePort;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "contractforge.security.cognito.issuer-uri=https://cognito.example.test",
        "contractforge.security.cognito.client-id=client-123",
        "contractforge.storage.s3.bucket=test-quarantine-bucket",
        "contractforge.storage.s3.region=eu-west-1"
})
@ActiveProfiles({"integration", "security-test"})
@Import({SecurityTestConfiguration.class, UploadHttpIntegrationTests.StorageTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UploadHttpIntegrationTests extends PostgresIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity()).build();
    }

    @Test
    void authenticatedInitiationUsesResolvedApplicationUserAndOneExpiry() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/uploads").header("Authorization", "Bearer access-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"contract.pdf\",\"contentType\":\"application/pdf\",\"size\":42,\"contractType\":\"ELECTRICITY\","
                                + "\"userId\":\"ffffffff-ffff-ffff-ffff-ffffffffffff\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploadId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("UPLOADING"))
                .andExpect(jsonPath("$.contractType").value("ELECTRICITY"))
                .andExpect(jsonPath("$.originalFilename").value("contract.pdf"))
                .andExpect(jsonPath("$.upload.url").value("https://s3.example.test/upload"))
                .andReturn();
        String response = result.getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(response, "$.expiresAt"))
                .isEqualTo(JsonPath.<String>read(response, "$.upload.expiresAt"));
        String uploadId = JsonPath.read(response, "$.uploadId");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uploads u JOIN user_identities i ON i.user_id = u.user_id "
                + "WHERE u.id = ? AND i.provider_subject = ?", Integer.class, UUID.fromString(uploadId), "owner")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT contract_type FROM uploads WHERE id = ?", String.class,
                UUID.fromString(uploadId))).isEqualTo("ELECTRICITY");
        mockMvc.perform(get("/v1/uploads/{id}", uploadId).header("Authorization", "Bearer access-owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UPLOADING"))
                .andExpect(jsonPath("$.contractType").value("ELECTRICITY"))
                .andExpect(jsonPath("$.originalFilename").value("contract.pdf"));
    }

    @Test
    void uploadInitiationRequiresASupportedContractType() throws Exception {
        mockMvc.perform(post("/v1/uploads").header("Authorization", "Bearer access-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"contract.pdf\",\"contentType\":\"application/pdf\",\"size\":42}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"INVALID_UPLOAD_REQUEST\"}"));

        mockMvc.perform(post("/v1/uploads").header("Authorization", "Bearer access-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"contract.pdf\",\"contentType\":\"application/pdf\",\"size\":42,\"contractType\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingAuthenticationIsRejected() throws Exception {
        mockMvc.perform(post("/v1/uploads").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"contract.pdf\",\"contentType\":\"application/pdf\",\"size\":42,\"contractType\":\"ELECTRICITY\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedDocumentDeletionIsRejected() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/v1/documents/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherAuthenticatedUserCannotReadTheOwnerUpload() throws Exception {
        String response = mockMvc.perform(post("/v1/uploads").header("Authorization", "Bearer access-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"contract.pdf\",\"contentType\":\"application/pdf\",\"size\":42,\"contractType\":\"ELECTRICITY\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String uploadId = JsonPath.read(response, "$.uploadId");

        mockMvc.perform(get("/v1/uploads/{id}", uploadId).header("Authorization", "Bearer access-other"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(Matchers.not(Matchers.containsString(uploadId))));
    }

    @Test
    void ownerCanDeleteDocumentAndAnotherUserCannot() throws Exception {
        mockMvc.perform(post("/v1/uploads").header("Authorization", "Bearer access-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"contract.pdf\",\"contentType\":\"application/pdf\",\"size\":42,\"contractType\":\"ELECTRICITY\"}"))
                .andExpect(status().isOk());
        UUID ownerId = jdbc.queryForObject("SELECT user_id FROM user_identities WHERE provider_subject = ?",
                UUID.class, "owner");
        UUID documentId = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, user_id, storage_key, filename, mime_type, size, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", documentId, ownerId,
                "private/trusted/documents/" + documentId, "contract.pdf", "application/pdf", 42, "SUCCESS");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/documents/{id}", documentId)
                        .header("Authorization", "Bearer access-other"))
                .andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/documents/{id}", documentId)
                        .header("Authorization", "Bearer access-owner"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE id = ?", Integer.class, documentId)).isZero();
    }

    @Test
    void ownerCanReadAuthoritativeDocumentProcessingStatusButOtherOwnersCannot() throws Exception {
        UUID ownerId = jdbc.queryForObject("SELECT user_id FROM user_identities WHERE provider_subject = ?",
                UUID.class, "owner");
        UUID documentId = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, user_id, storage_key, filename, mime_type, size, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", documentId, ownerId,
                "private/trusted/documents/" + documentId, "contract.pdf", "application/pdf", 42, "PROCESSING");

        mockMvc.perform(get("/v1/documents/{id}", documentId).header("Authorization", "Bearer access-owner"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"documentId\":\"" + documentId + "\",\"status\":\"PROCESSING\","
                        + "\"contractType\":\"ELECTRICITY\",\"originalFilename\":\"contract.pdf\",\"failureCategory\":null}"));
        jdbc.update("UPDATE documents SET status = 'SUCCESS' WHERE id = ?", documentId);
        mockMvc.perform(get("/v1/documents/{id}", documentId).header("Authorization", "Bearer access-owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.contractType").value("ELECTRICITY"))
                .andExpect(jsonPath("$.originalFilename").value("contract.pdf"))
                .andExpect(jsonPath("$.failureCategory").value(org.hamcrest.Matchers.nullValue()));
        jdbc.update("UPDATE documents SET status = 'FAILED' WHERE id = ?", documentId);
        mockMvc.perform(get("/v1/documents/{id}", documentId).header("Authorization", "Bearer access-owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureCategory").value("UNKNOWN"));
        mockMvc.perform(get("/v1/documents/{id}", documentId).header("Authorization", "Bearer access-other"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(Matchers.not(Matchers.containsString(documentId.toString()))));
    }

    @Test
    void processingStatusMapsOnlyRecognizedFailureEvidenceToSafeCategories() throws Exception {
        mockMvc.perform(post("/v1/uploads").header("Authorization", "Bearer access-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":\"contract.pdf\",\"contentType\":\"application/pdf\",\"size\":42,\"contractType\":\"ELECTRICITY\"}"))
                .andExpect(status().isOk());
        UUID ownerId = jdbc.queryForObject("SELECT user_id FROM user_identities WHERE provider_subject = ?",
                UUID.class, "owner");
        assertFailureCategory(ownerId, "RETRYABLE_PROCESSING_FAILURE", "RETRYABLE");
        assertFailureCategory(ownerId, "RetryableProcessingException", "RETRYABLE");
        assertFailureCategory(ownerId, "RESOURCE_LIMIT_PAGE_COUNT", "DETERMINISTIC_DOCUMENT");
        assertFailureCategory(ownerId, "MalformedDocumentException", "DETERMINISTIC_DOCUMENT");
        assertFailureCategory(ownerId, "ProviderSpecificInternalFailure", "UNKNOWN");
    }

    private void assertFailureCategory(UUID ownerId, String privateErrorCode, String expectedCategory) throws Exception {
        UUID documentId = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, user_id, storage_key, filename, mime_type, size, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'FAILED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", documentId, ownerId,
                "private/trusted/documents/" + documentId, "contract.pdf", "application/pdf", 42);
        jdbc.update("INSERT INTO processing_attempts (id, document_id, attempt_number, status, started_at, completed_at, error_code) "
                        + "VALUES (?, ?, 1, 'FAILED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)",
                UUID.randomUUID(), documentId, privateErrorCode);

        String response = mockMvc.perform(get("/v1/documents/{id}", documentId).header("Authorization", "Bearer access-owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.failureCategory").value(expectedCategory))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(privateErrorCode);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageTestConfiguration {
        @Bean
        @Primary
        ObjectStoragePort objectStoragePort() {
            return (objectKey, contentType, contentLength) -> new ObjectStoragePort.UploadInstructions(
                    URI.create("https://s3.example.test/upload"),
                    Map.of("Content-Type", contentType, "Content-Length", Long.toString(contentLength)));
        }

        @Bean
        @Primary
        DocumentDeletionQueuePort documentDeletionQueuePort() {
            return message -> { };
        }
    }
}
