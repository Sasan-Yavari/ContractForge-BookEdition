package io.contractforge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.contractforge.backend.api.security.SecurityTestConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "contractforge.security.cognito.issuer-uri=https://cognito.example.test",
        "contractforge.security.cognito.client-id=client-123",
        "contractforge.storage.s3.bucket=test-quarantine-bucket",
        "contractforge.storage.s3.region=eu-west-1",
        "contractforge.api.upload-rate-limit.maximum-requests=2",
        "contractforge.api.upload-rate-limit.window=PT1M",
        "contractforge.api.upload-rate-limit.max-tracked-users=2"
})
@ActiveProfiles({"integration", "security-test"})
@Import({SecurityTestConfiguration.class,
        UploadHttpIntegrationTests.StorageTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UploadRateLimitHttpIntegrationTests extends PostgresIntegrationTest {
    private static final String UPLOAD_REQUEST = "{\"filename\":\"contract.pdf\",\"contentType\":\"application/pdf\","
            + "\"size\":42,\"contractType\":\"ELECTRICITY\"}";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    void limitsByResolvedApplicationUserAndIgnoresCallerForwardingHeaders() throws Exception {
        String firstForwardedFor = "198.51.100.21";
        String secondForwardedFor = "198.51.100.22";
        MvcResult first = initiate("owner", firstForwardedFor)
                .andExpect(status().isOk())
                .andReturn();
        initiate("owner", secondForwardedFor)
                .andExpect(status().isOk());

        String firstUploadId = JsonPath.read(first.getResponse().getContentAsString(), "$.uploadId");
        MvcResult rejected = initiate("owner", "203.0.113.99")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value("UPLOAD_RATE_LIMIT_EXCEEDED"))
                .andReturn();

        long retryAfter = Long.parseLong(rejected.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 60L);
        assertThat(rejected.getResponse().getContentAsString())
                .doesNotContain("owner", "example.test", firstForwardedFor, secondForwardedFor, "203.0.113.99");
        mockMvc.perform(get("/v1/uploads/{id}", firstUploadId).header("Authorization", "Bearer access-owner"))
                .andExpect(status().isOk());

        initiate("other", "198.51.100.21").andExpect(status().isOk());
        mockMvc.perform(post("/v1/uploads")
                        .header("Authorization", "Bearer access-third")
                        .header("X-Forwarded-For", "198.51.100.21")
                        .contentType("application/json")
                        .content(UPLOAD_REQUEST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("UPLOAD_INITIATION_TEMPORARILY_UNAVAILABLE"));

        assertThat(uploadCount("owner")).isEqualTo(2);
        assertThat(uploadCount("other")).isEqualTo(1);
        assertThat(uploadCount("third")).isZero();
    }

    private ResultActions initiate(String subject, String forwardedFor) throws Exception {
        return mockMvc.perform(post("/v1/uploads")
                .header("Authorization", "Bearer access-" + subject)
                .header("X-Forwarded-For", forwardedFor)
                .header("Forwarded", "for=" + forwardedFor + ";proto=https")
                .header("X-Real-IP", forwardedFor)
                .contentType("application/json")
                .content(UPLOAD_REQUEST));
    }

    private int uploadCount(String subject) {
        UUID userId = jdbc.queryForObject("SELECT user_id FROM user_identities WHERE provider_subject = ?",
                UUID.class, subject);
        return jdbc.queryForObject("SELECT COUNT(*) FROM uploads WHERE user_id = ?", Integer.class, userId);
    }
}
