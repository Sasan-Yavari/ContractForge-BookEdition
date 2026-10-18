package io.contractforge.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.contractforge.backend.api.security.SecurityTestConfiguration;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest(properties = {
        "contractforge.security.cognito.issuer-uri=https://cognito.example.test",
        "contractforge.security.cognito.client-id=client-123"
})
@ActiveProfiles({"test", "security-test"})
@Import({SecurityTestConfiguration.class, SecurityIntegrationTests.ProtectedTestConfiguration.class})
class SecurityIntegrationTests {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"));
    }

    @Test
    void protectedApiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/v1/protected-test"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validCognitoAccessTokenUsesServerResolvedProfileAttributes() throws Exception {
        mockMvc.perform(get("/v1/protected-test").header("Authorization", "Bearer access"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"provider\":\"cognito\",\"subject\":\"subject-123\","
                        + "\"email\":\"subject-123@example.test\",\"emailVerified\":true}"));
    }

    @Test
    void idTokensAndInvalidTokenClaimsAreRejected() throws Exception {
        for (String token : new String[]{"id", "wrong-issuer", "wrong-client", "malformed"}) {
            mockMvc.perform(get("/v1/protected-test").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(token))));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProtectedTestConfiguration {
        @Bean
        ProtectedTestController protectedTestController() {
            return new ProtectedTestController();
        }
    }

    @RestController
    static class ProtectedTestController {
        @GetMapping("/v1/protected-test")
        MapResponse principal(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
            return new MapResponse(principal.provider(), principal.providerSubject(), principal.email(),
                    principal.emailVerified());
        }
    }

    record MapResponse(String provider, String subject, String email, boolean emailVerified) {
    }
}
