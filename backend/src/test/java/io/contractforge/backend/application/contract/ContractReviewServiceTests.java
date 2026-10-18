package io.contractforge.backend.application.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.audit.AuditEventService;
import io.contractforge.backend.application.monitoring.ContractMonitoringExecutionService;
import io.contractforge.backend.application.extraction.ContractExtractionPort;
import io.contractforge.backend.application.extraction.ElectricityContractCandidateValidator;
import io.contractforge.backend.application.port.RepositoryPorts;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import io.contractforge.backend.domain.model.PersistenceModels;
import io.contractforge.backend.domain.model.PersistenceModels.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContractReviewServiceTests {
    private static final UUID USER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID DOCUMENT = UUID.randomUUID();
    private RepositoryPorts.ContractRepository contracts;
    private RepositoryPorts.DocumentRepository documents;
    private ContractReviewService service;
    private ContractAggregate draft;

    @BeforeEach
    void setUp() {
        contracts = new Contracts();
        documents = new Documents();
        AuditEventService audit = new AuditEventService(event -> { });
        service = new ContractReviewService(contracts, documents, new ElectricityContractCandidateValidator(new ObjectMapper()), audit, new ObjectMapper(),
                new ContractMonitoringExecutionService(null, null, null, null, null, null,
                        recommendation -> { }, java.time.Clock.systemUTC()));
        OffsetDateTime now = OffsetDateTime.now();
        draft = new ContractAggregate(new Contract(DOCUMENT, "ELECTRICITY", "DRAFT",
                new JsonValue("{\"schema_version\":\"" + ContractExtractionPort.CURRENT_SCHEMA_VERSION + "\"}"), 2, now, now, null),
                List.of(new ContractParty(UUID.randomUUID(), DOCUMENT, "CUSTOMER", "Ada")),
                new ContractTerms(DOCUMENT, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), 12,
                        "FIXED_TERM", "FIXED", new JsonValue("{\"consumption\":{\"annual_kwh\":100},\"pricing\":{\"model\":\"FIXED\"}}")));
        ((Documents) documents).document = new Document(DOCUMENT, USER, "private/key", "contract.pdf",
                "application/pdf", 1, "SUCCESS", now, now);
        ((Contracts) contracts).aggregate = draft;
    }

    @Test
    void ownerCanReadDraftButAnotherUserGetsNotFound() {
        assertThat(service.read(new AuthenticatedApplicationUser(USER), DOCUMENT).status()).isEqualTo("DRAFT");
        assertThatThrownBy(() -> service.read(new AuthenticatedApplicationUser(OTHER), DOCUMENT))
                .isInstanceOf(ContractReviewService.ContractNotFoundException.class);
    }

    @Test
    void invalidEditDoesNotCallRepository() {
        var invalid = new ContractReviewService.Edit(2,
                List.of(new ContractReviewService.Party("CUSTOMER", "")),
                new ContractReviewService.Terms(null, null, 0, "FIXED_TERM", "FIXED",
                        new ObjectMapper().createObjectNode()));
        assertThatThrownBy(() -> service.edit(new AuthenticatedApplicationUser(USER), DOCUMENT, invalid))
                .isInstanceOf(ContractReviewService.InvalidEditException.class);
        assertThat(((Contracts) contracts).updates).isZero();
    }

    @Test
    void currentVersionEditCallsGuardedUpdateAndStaleVersionFails() {
        var electricity = new ObjectMapper().createObjectNode();
        electricity.putObject("consumption").put("annual_kwh", 200);
        electricity.putObject("pricing").put("model", "FIXED");
        var edit = new ContractReviewService.Edit(2, List.of(new ContractReviewService.Party("CUSTOMER", "Grace")),
                new ContractReviewService.Terms(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), 12,
                        "FIXED_TERM", "FIXED", electricity));
        service.edit(new AuthenticatedApplicationUser(USER), DOCUMENT, edit);
        assertThat(((Contracts) contracts).updates).isEqualTo(1);

        var stale = new ContractReviewService.Edit(1, edit.parties(), edit.terms());
        assertThatThrownBy(() -> service.edit(new AuthenticatedApplicationUser(USER), DOCUMENT, stale))
                .isInstanceOf(ContractReviewService.ConflictException.class);
        assertThat(((Contracts) contracts).updates).isEqualTo(1);
    }

    private static final class Documents implements RepositoryPorts.DocumentRepository {
        private Document document;
        public Optional<Document> findById(UUID id) { return Optional.ofNullable(document); }
        public Optional<Document> findByIdForUpdate(UUID id) { return findById(id); }
        public void save(Document value) { document = value; }
        public void markProcessingSucceeded(UUID id) { }
        public int markProcessingFailed(UUID id) { return 0; }
        public void deleteById(UUID id) { }
    }

    private static final class Contracts implements RepositoryPorts.ContractRepository {
        private ContractAggregate aggregate;
        private int updates;
        public Optional<ContractAggregate> findByDocumentId(UUID id) { return Optional.ofNullable(aggregate); }
        public List<MonitoringContract> findEligibleForMonitoring() { return List.of(); }
        public void save(ContractAggregate value) { aggregate = value; }
        public int updateDraft(ContractAggregate value, long expectedVersion) {
            updates++;
            aggregate = new ContractAggregate(new Contract(value.contract().documentId(), value.contract().type(),
                    value.contract().status(), value.contract().data(), expectedVersion + 1,
                    value.contract().createdAt(), value.contract().updatedAt(), value.contract().archivedAt()),
                    value.parties(), value.terms());
            return 1;
        }
        public int updateActive(ContractAggregate value, long expectedVersion) { return updateDraft(value, expectedVersion); }
        public int approveDraft(UUID id, long expectedVersion) { return 1; }
    }
}
