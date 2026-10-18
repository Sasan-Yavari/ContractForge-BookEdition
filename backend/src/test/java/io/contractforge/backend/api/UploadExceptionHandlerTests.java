package io.contractforge.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.contractforge.backend.application.upload.UploadInitiationRateLimitExceededException;
import io.contractforge.backend.application.upload.UploadRateLimitCapacityExceededException;
import io.contractforge.backend.application.accountdeletion.AccountDeletionDisablePendingException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class UploadExceptionHandlerTests {
    @Test
    void rateLimitResponseUsesSafeErrorEnvelopeAndRetryAfter() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RateLimitTestController())
                .setControllerAdvice(new UploadExceptionHandler())
                .build();

        mockMvc.perform(get("/rate-limit"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "17"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"error\":\"UPLOAD_RATE_LIMIT_EXCEEDED\"}"));
    }

    @Test
    void saturatedTrackerUsesServiceUnavailableAndRetryAfter() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RateLimitTestController())
                .setControllerAdvice(new UploadExceptionHandler())
                .build();

        mockMvc.perform(get("/rate-limit-capacity"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "23"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"error\":\"UPLOAD_INITIATION_TEMPORARILY_UNAVAILABLE\"}"));
    }

    @Test
    void pendingCognitoDisableReturnsRetryableServiceUnavailableWithoutSensitiveDetails() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RateLimitTestController())
                .setControllerAdvice(new UploadExceptionHandler())
                .build();

        mockMvc.perform(get("/account-deletion-pending"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "17"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"error\":\"ACCOUNT_DELETION_DISABLE_PENDING\"}"));
    }

    @RestController
    private static class RateLimitTestController {
        @GetMapping("/rate-limit")
        Map<String, String> rateLimited() {
            throw new UploadInitiationRateLimitExceededException(17);
        }

        @GetMapping("/rate-limit-capacity")
        Map<String, String> capacityUnavailable() {
            throw new UploadRateLimitCapacityExceededException(23);
        }

        @GetMapping("/account-deletion-pending")
        Map<String, String> accountDeletionPending() {
            throw new AccountDeletionDisablePendingException(17);
        }
    }
}
