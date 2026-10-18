package io.contractforge.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.contractforge.backend.api.security.SecurityTestConfiguration;
import io.contractforge.backend.application.accountdeletion.AccountDeletionRepository.ExternalIdentity;
import io.contractforge.backend.application.accountdeletion.AccountDeletionTombstonePort;
import io.contractforge.backend.application.audit.AuditEventService;
import io.contractforge.backend.application.identity.ApplicationAccessDeniedException;
import io.contractforge.backend.application.identity.IdentityProvisioningService;
import io.contractforge.backend.application.identity.IdentityResolutionService;
import io.contractforge.backend.application.identity.ProviderSubjectAlreadyExistsException;
import io.contractforge.backend.application.notification.NotificationGenerationService;
import io.contractforge.backend.application.port.RepositoryPorts.RecommendationRepository;
import io.contractforge.backend.application.port.RepositoryPorts.UserIdentityRepository;
import io.contractforge.backend.application.port.RepositoryPorts.UserRepository;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import io.contractforge.backend.domain.model.PersistenceModels.Recommendation;
import io.contractforge.backend.domain.model.PersistenceModels.User;
import io.contractforge.backend.domain.model.PersistenceModels.UserIdentity;
import io.contractforge.backend.domain.model.UserStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=",
        "contractforge.security.cognito.issuer-uri=https://cognito.example.test",
        "contractforge.security.cognito.client-id=client-123"
})
@ActiveProfiles({"integration", "security-test"})
@Import({SecurityTestConfiguration.class, IdentityResolutionIntegrationTests.IdentityTestConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdentityResolutionIntegrationTests extends PostgresIntegrationTest {

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PROVIDER = "cognito";
    private static final String VALID_RECOMMENDATION_DATA = "{\"reason\":{\"type\":\"CONTRACT_EXPIRY\"},"
            + "\"evidence\":{\"event_type\":\"CONTRACT_EXPIRING\",\"event_date\":\"2026-01-01T00:00:00Z\"},"
            + "\"impact\":{\"type\":\"NONE\"},\"action\":{\"type\":\"REVIEW\"}}";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository users;

    @Autowired
    @Qualifier("userIdentityRepository")
    private UserIdentityRepository identities;

    @Autowired
    private RecommendationRepository recommendations;

    @Autowired
    private NotificationGenerationService notificationGeneration;

    @Autowired
    private IdentityResolutionService resolutionService;

    @Autowired
    private AccountDeletionTombstonePort tombstones;

    @Autowired
    @Qualifier("raceIdentityRepository")
    private RaceSynchronizingIdentityRepository raceIdentityRepository;

    @Autowired
    @Qualifier("rollbackProvisioningService")
    private IdentityProvisioningService rollbackProvisioningService;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @AfterEach
    void cleanTestData() {
        jdbc.update("DELETE FROM user_identities WHERE provider = ? AND (provider_subject LIKE 'issue27-%' "
                        + "OR provider_subject LIKE 'issue93-%')",
                PROVIDER);
        jdbc.update("DELETE FROM audit_events WHERE resource_type = 'USER' AND action = 'CREATED' "
                        + "AND resource_id <> ?", SYSTEM_USER_ID);
        jdbc.update("DELETE FROM users WHERE id <> ? AND id NOT IN (SELECT user_id FROM user_identities)",
                SYSTEM_USER_ID);
    }

    @Test
    void firstUseCreatesOneActiveUserAndRepeatedResolutionIsIdempotent() {
        AuthenticatedPrincipal principal = principal("issue27-first");

        AuthenticatedApplicationUser first = resolutionService.resolve(principal);
        AuthenticatedApplicationUser second = resolutionService.resolve(principal);

        assertThat(first.userId()).isEqualTo(second.userId());
        assertThat(count("SELECT count(*) FROM user_identities WHERE provider = ? AND provider_subject = ?",
                PROVIDER, principal.providerSubject())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM users WHERE id = ? AND status = 'ACTIVE'", first.userId()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_events WHERE user_id = ? AND resource_type = 'USER' "
                        + "AND resource_id = ? AND action = 'CREATED'", first.userId(), first.userId()))
                .isEqualTo(1);
    }

    @Test
    void firstVerifiedAuthenticationPersistsNormalizedTrustedEmail() {
        AuthenticatedPrincipal principal = principal("issue93-first", " Owner@Example.Test ", true);

        AuthenticatedApplicationUser resolved = resolutionService.resolve(principal);

        assertThat(jdbc.queryForObject("SELECT email FROM user_identities WHERE provider = ? AND provider_subject = ?",
                String.class, PROVIDER, principal.providerSubject())).isEqualTo("owner@example.test");
        assertThat(count("SELECT count(*) FROM users WHERE id = ?", resolved.userId())).isEqualTo(1);
    }

    @Test
    void existingIdentityRefreshesEmailWithoutChangingUserOrIdentityAndNotificationDestination() {
        AuthenticatedApplicationUser first = resolutionService.resolve(
                principal("issue93-refresh", "old@example.test", true));
        UUID identityId = jdbc.queryForObject("SELECT id FROM user_identities WHERE provider = ? AND provider_subject = ?",
                UUID.class, PROVIDER, "issue93-refresh");

        AuthenticatedApplicationUser changed = resolutionService.resolve(
                principal("issue93-refresh", "New@Example.Test", true));
        AuthenticatedApplicationUser unchanged = resolutionService.resolve(
                principal("issue93-refresh", "new@example.test", true));

        assertThat(changed.userId()).isEqualTo(first.userId());
        assertThat(unchanged.userId()).isEqualTo(first.userId());
        assertThat(jdbc.queryForObject("SELECT email FROM user_identities WHERE id = ?", String.class, identityId))
                .isEqualTo("new@example.test");
        assertThat(count("SELECT count(*) FROM user_identities WHERE provider = ? AND provider_subject = ?",
                PROVIDER, "issue93-refresh")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM users WHERE id = ?", first.userId())).isEqualTo(1);

        UUID documentId = UUID.randomUUID();
        UUID recommendationId = UUID.randomUUID();
        try {
            OffsetDateTime now = OffsetDateTime.now();
            jdbc.update("INSERT INTO documents (id, user_id, storage_key, filename, mime_type, size, status, "
                    + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'SUCCESS', ?, ?)", documentId, first.userId(),
                    "issue93", "issue93.pdf", "application/pdf", 1, now, now);
            Recommendation recommendation = new Recommendation(recommendationId, first.userId(), documentId,
                    "CONTRACT_EXPIRY", new JsonValue(VALID_RECOMMENDATION_DATA), now, null);
            recommendations.saveIfAbsent(recommendation);
            notificationGeneration.generate(recommendation);

            assertThat(jdbc.queryForObject("SELECT d.destination FROM notification_deliveries d "
                    + "JOIN notifications n ON n.id = d.notification_id WHERE n.recommendation_id = ?",
                    String.class, recommendationId)).isEqualTo("new@example.test");
        } finally {
            jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
        }
    }

    @Test
    void unusableExistingProviderEmailClearsTrustedDeliveryDestination() {
        AuthenticatedPrincipal[] claims = {
            principal("issue93-unusable", "trusted@example.test", true),
            principal("issue93-unusable", null, true),
            principal("issue93-unusable", "ignored@example.test", false),
            principal("issue93-unusable", "not-an-email", true)
        };

        resolutionService.resolve(claims[0]);
        for (int i = 1; i < claims.length; i++) {
            resolutionService.resolve(claims[i]);
            assertThat(jdbc.queryForObject("SELECT email FROM user_identities WHERE provider = ? AND provider_subject = ?",
                    String.class, PROVIDER, "issue93-unusable")).isNull();
        }
    }

    @Test
    void failedIdentityWriteRollsBackTheCandidateUser() {
        AuthenticatedPrincipal principal = principal("issue27-rollback");

        assertThatThrownBy(() -> rollbackProvisioningService.provision(principal))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("controlled unrelated failure");

        assertThat(count("SELECT count(*) FROM user_identities WHERE provider_subject = ?",
                principal.providerSubject())).isZero();
        assertThat(count("SELECT count(*) FROM users WHERE id <> ? AND id NOT IN (SELECT user_id FROM user_identities)",
                SYSTEM_USER_ID)).isZero();
        assertThat(count("SELECT count(*) FROM audit_events WHERE resource_type = 'USER' AND action = 'CREATED' "
                        + "AND resource_id <> ?", SYSTEM_USER_ID)).isZero();
    }

    @Test
    void concurrentFirstUseConvergesOnOneIdentityAndApplicationUser() throws Exception {
        AuthenticatedPrincipal principal = principal("issue27-race");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AuthenticatedApplicationUser> first = executor.submit(() -> resolutionService.resolve(principal));
            Future<AuthenticatedApplicationUser> second = executor.submit(() -> resolutionService.resolve(principal));

            UUID firstUserId = userId(first);
            UUID secondUserId = userId(second);
            assertThat(secondUserId).isEqualTo(firstUserId);
            assertThat(raceIdentityRepository.insertAttempts()).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM user_identities WHERE provider = ? AND provider_subject = ?",
                    PROVIDER, principal.providerSubject())).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM users WHERE id = ?", firstUserId)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM users WHERE id <> ? AND id NOT IN (SELECT user_id FROM user_identities)",
                    SYSTEM_USER_ID)).isZero();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void unrelatedIntegrityFailureIsNotReinterpretedAsRaceSuccess() {
        AuthenticatedPrincipal principal = principal("issue27-unrelated");

        assertThatThrownBy(() -> rollbackProvisioningService.provision(principal))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("controlled unrelated failure");
    }

    @Test
    void intendedProviderSubjectConstraintIsTranslated() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID identityId = UUID.randomUUID();
        identities.save(new UserIdentity(identityId, SYSTEM_USER_ID, PROVIDER, "issue27-constraint", now));

        assertThatThrownBy(() -> identities.save(new UserIdentity(UUID.randomUUID(), SYSTEM_USER_ID, PROVIDER,
                "issue27-constraint", now)))
                .isInstanceOf(ProviderSubjectAlreadyExistsException.class);
    }

    @Test
    void unrelatedUniqueConstraintPropagatesUnchanged() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID identityId = UUID.randomUUID();
        identities.save(new UserIdentity(identityId, SYSTEM_USER_ID, PROVIDER, "issue27-primary-key", now));

        assertThatThrownBy(() -> identities.save(new UserIdentity(identityId, SYSTEM_USER_ID, PROVIDER,
                "issue27-different-subject", now)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(ProviderSubjectAlreadyExistsException.class);
    }

    @Test
    void disabledMappedUserIsDeniedEvenWithValidExternalAuthentication() {
        UUID userId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        saveDisabledIdentity(userId, "issue27-disabled", now);

        assertThatThrownBy(() -> resolutionService.resolve(principal("issue27-disabled")))
                .isInstanceOf(ApplicationAccessDeniedException.class);
        assertThatThrownBy(() -> identities.save(new UserIdentity(UUID.randomUUID(), userId, PROVIDER,
                "issue27-disabled-new", now)))
                .isInstanceOf(UncategorizedSQLException.class)
                .satisfies(failure -> assertThat(((UncategorizedSQLException) failure).getSQLException().getSQLState())
                        .isEqualTo("55000"));
    }

    @Test
    void deletedProviderSubjectCannotBeReprovisioned() {
        String subject = "issue173-deleted-subject";
        UUID deletedUserId = UUID.randomUUID();
        tombstones.recordInProgress(UUID.randomUUID(), deletedUserId,
                java.util.List.of(new ExternalIdentity(PROVIDER, subject)), OffsetDateTime.now());

        assertThatThrownBy(() -> resolutionService.resolve(principal(subject)))
                .isInstanceOf(ApplicationAccessDeniedException.class);
        assertThat(count("SELECT COUNT(*) FROM users WHERE id = ?", deletedUserId)).isZero();
        assertThat(identities.findByProviderSubject(PROVIDER, subject)).isEmpty();
    }

    @Test
    void securityBoundaryPropagatesResolvedUserAndIgnoresSuppliedUserId() throws Exception {
        UUID suppliedUserId = UUID.fromString("70000000-0000-0000-0000-000000000001");
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();

        mockMvc.perform(get("/v1/application-identity-test")
                        .param("userId", suppliedUserId.toString())
                        .header("Authorization", "Bearer access-issue27-http"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(suppliedUserId.toString()))));
    }

    @Test
    void disabledApplicationUserProducesForbiddenAtProtectedBoundary() throws Exception {
        UUID userId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        saveDisabledIdentity(userId, "issue27-disabled-http", now);

        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        mockMvc.perform(get("/v1/application-identity-test")
                        .header("Authorization", "Bearer access-issue27-disabled-http"))
                .andExpect(status().isForbidden());
    }

    private AuthenticatedPrincipal principal(String subject) {
        return new AuthenticatedPrincipal(PROVIDER, subject);
    }

    private void saveDisabledIdentity(UUID userId, String providerSubject, OffsetDateTime now) {
        users.save(new User(userId, UserStatus.ACTIVE, now, now));
        identities.save(new UserIdentity(UUID.randomUUID(), userId, PROVIDER, providerSubject, now));
        jdbc.update("UPDATE users SET status = ? WHERE id = ?", UserStatus.DISABLED.value(), userId);
    }

    private AuthenticatedPrincipal principal(String subject, String email, boolean verified) {
        return new AuthenticatedPrincipal(PROVIDER, subject, email, verified);
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private UUID userId(Future<AuthenticatedApplicationUser> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(10, TimeUnit.SECONDS).userId();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IdentityTestConfiguration {
        @Bean
        IdentityProvisioningService rollbackProvisioningService(
                UserRepository users, @Qualifier("userIdentityRepository") UserIdentityRepository realIdentities,
                AuditEventService auditEventService) {
            return new IdentityProvisioningService(users, new FailingIdentityRepository(realIdentities), auditEventService);
        }

        @Bean
        RaceSynchronizingIdentityRepository raceIdentityRepository(
                @Qualifier("userIdentityRepository") UserIdentityRepository realIdentities) {
            return new RaceSynchronizingIdentityRepository(realIdentities);
        }

        @Bean
        IdentityProvisioningService synchronizedProvisioningService(
                UserRepository users, @Qualifier("raceIdentityRepository") UserIdentityRepository raceIdentities,
                AuditEventService auditEventService) {
            return new IdentityProvisioningService(users, raceIdentities, auditEventService);
        }

        @Bean
        @Primary
        IdentityResolutionService synchronizedIdentityResolutionService(
                UserRepository users,
                @Qualifier("userIdentityRepository") UserIdentityRepository realIdentities,
                @Qualifier("synchronizedProvisioningService") IdentityProvisioningService provisioningService,
                AccountDeletionTombstonePort tombstones) {
            return new IdentityResolutionService(users, realIdentities, provisioningService, tombstones);
        }

        @Bean
        IdentityTestController identityTestController(IdentityResolutionService resolutionService) {
            return new IdentityTestController(resolutionService);
        }
    }

    static class FailingIdentityRepository implements UserIdentityRepository {
        private final UserIdentityRepository delegate;

        FailingIdentityRepository(UserIdentityRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.Optional<UserIdentity> findByProviderSubject(String provider, String providerSubject) {
            return delegate.findByProviderSubject(provider, providerSubject);
        }

        @Override
        public java.util.Optional<String> findTrustedEmailByUserId(UUID userId) {
            return delegate.findTrustedEmailByUserId(userId);
        }

        @Override
        public void updateTrustedEmail(UUID identityId, String email) {
            delegate.updateTrustedEmail(identityId, email);
        }

        @Override
        public void save(UserIdentity identity) {
            delegate.save(identity);
            if (identity.providerSubject().startsWith("issue27-")) {
                throw new DataIntegrityViolationException("controlled unrelated failure");
            }
        }
    }

    static class RaceSynchronizingIdentityRepository implements UserIdentityRepository {
        private final UserIdentityRepository delegate;
        private final CountDownLatch bothAttemptsEntered = new CountDownLatch(2);
        private final CountDownLatch releaseInsert = new CountDownLatch(1);
        private final AtomicInteger insertAttempts = new AtomicInteger();

        RaceSynchronizingIdentityRepository(UserIdentityRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public java.util.Optional<UserIdentity> findByProviderSubject(String provider, String providerSubject) {
            return delegate.findByProviderSubject(provider, providerSubject);
        }

        @Override
        public java.util.Optional<String> findTrustedEmailByUserId(UUID userId) {
            return delegate.findTrustedEmailByUserId(userId);
        }

        @Override
        public void updateTrustedEmail(UUID identityId, String email) {
            delegate.updateTrustedEmail(identityId, email);
        }

        @Override
        public void save(UserIdentity identity) {
            if ("issue27-race".equals(identity.providerSubject())) {
                insertAttempts.incrementAndGet();
                bothAttemptsEntered.countDown();
                if (bothAttemptsEntered.getCount() == 0) {
                    releaseInsert.countDown();
                }
                await(releaseInsert);
            }
            delegate.save(identity);
        }

        int insertAttempts() {
            return insertAttempts.get();
        }

        private void await(CountDownLatch latch) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent identity test did not reach both inserts");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Concurrent identity test was interrupted", exception);
            }
        }
    }

    @RestController
    static class IdentityTestController {
        private final IdentityResolutionService resolutionService;

        IdentityTestController(IdentityResolutionService resolutionService) {
            this.resolutionService = resolutionService;
        }

        @GetMapping("/v1/application-identity-test")
        ResponseEntity<Map<String, UUID>> resolve(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                                  @RequestParam(required = false) UUID userId) {
            try {
                return ResponseEntity.ok(Map.of("userId", resolutionService.resolve(principal).userId()));
            } catch (ApplicationAccessDeniedException exception) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }
    }
}
