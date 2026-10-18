package io.contractforge.backend.application.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.audit.AuditEventService;
import io.contractforge.backend.application.extraction.ContractExtractionPort;
import io.contractforge.backend.application.extraction.ElectricityContractCandidateValidator;
import io.contractforge.backend.application.monitoring.ContractMonitoringExecutionService;
import io.contractforge.backend.application.port.RepositoryPorts.ContractRepository;
import io.contractforge.backend.application.port.RepositoryPorts.DocumentRepository;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import io.contractforge.backend.domain.model.ContractStatus;
import io.contractforge.backend.domain.model.PersistenceModels;
import io.contractforge.backend.domain.model.PersistenceModels.ContractAggregate;
import io.contractforge.backend.domain.model.PersistenceModels.ContractParty;
import io.contractforge.backend.domain.model.PersistenceModels.ContractTerms;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ContractReviewService {
    private final ContractRepository contracts;
    private final DocumentRepository documents;
    private final ElectricityContractCandidateValidator validator;
    private final AuditEventService audit;
    private final ObjectMapper json;
    private final ContractMonitoringExecutionService monitoring;

    public ContractReviewService(ContractRepository contracts, DocumentRepository documents,
                                 ElectricityContractCandidateValidator validator, AuditEventService audit,
                                 ObjectMapper json, ContractMonitoringExecutionService monitoring) {
        this.contracts = contracts; this.documents = documents; this.validator = validator;
        this.audit = audit; this.json = json; this.monitoring = monitoring;
    }

    @Transactional(readOnly = true)
    public Review read(AuthenticatedApplicationUser user, UUID documentId) {
        ContractAggregate aggregate = ownedDraftOrActive(user, documentId);
        return Review.from(aggregate, json);
    }

    @Transactional
    public Review edit(AuthenticatedApplicationUser user, UUID documentId, Edit edit) {
        ContractAggregate current = ownedDraftOrActive(user, documentId);
        if (current.contract().status() != ContractStatus.DRAFT
                && current.contract().status() != ContractStatus.ACTIVE) {
            throw new NotDraftException();
        }
        if (current.contract().version() != edit.expectedVersion()) throw new ConflictException();
        ContractAggregate replacement = edit.toAggregate(documentId, current.contract());
        try {
            validator.validate(new ContractExtractionPort.Candidate(ContractExtractionPort.CURRENT_SCHEMA_VERSION,
                    replacement.parties().stream().map(p -> new ContractExtractionPort.Party(p.role().value(), p.name())).toList(),
                    replacement.terms() == null ? null : replacement.terms().startDate(),
                    replacement.terms() == null ? null : replacement.terms().endDate(),
                    replacement.terms() == null ? null : replacement.terms().contractDuration(),
                    replacement.terms() == null || replacement.terms().renewalType() == null ? null : replacement.terms().renewalType().value(),
                    replacement.terms() == null || replacement.terms().pricingModel() == null ? null : replacement.terms().pricingModel().value(),
                    replacement.terms() == null ? null : replacement.terms().electricityData()));
        } catch (ElectricityContractCandidateValidator.InvalidCandidateException invalid) {
            throw new InvalidEditException();
        }
        int updated = current.contract().status() == ContractStatus.DRAFT
                ? contracts.updateDraft(replacement, edit.expectedVersion())
                : contracts.updateActive(replacement, edit.expectedVersion());
        if (updated != 1) throw new ConflictException();
        if (current.contract().status() == ContractStatus.ACTIVE) {
            monitoring.refreshConfirmedContract(user.userId(), replacement);
            audit.append(user.userId(), AuditEventService.ResourceType.CONTRACT, documentId, "CONFIRMED_EDITED");
        } else {
            audit.append(user.userId(), AuditEventService.ResourceType.CONTRACT, documentId, "DRAFT_EDITED");
        }
        return read(user, documentId);
    }

    @Transactional
    public Review approve(AuthenticatedApplicationUser user, UUID documentId, long expectedVersion) {
        ContractAggregate current = ownedDraft(user, documentId);
        if (current.contract().version() != expectedVersion) throw new ConflictException();
        if (contracts.approveDraft(documentId, expectedVersion) != 1) throw new ConflictException();
        audit.append(user.userId(), AuditEventService.ResourceType.CONTRACT, documentId, "DRAFT_APPROVED");
        return read(user, documentId);
    }

    private ContractAggregate ownedDraft(AuthenticatedApplicationUser user, UUID documentId) {
        ContractAggregate aggregate = ownedDraftOrActive(user, documentId);
        if (aggregate.contract().status() != io.contractforge.backend.domain.model.ContractStatus.DRAFT) throw new NotDraftException();
        return aggregate;
    }

    private ContractAggregate ownedDraftOrActive(AuthenticatedApplicationUser user, UUID documentId) {
        if (user == null || user.userId() == null || documentId == null) throw new ContractNotFoundException();
        return documents.findById(documentId).filter(d -> user.userId().equals(d.userId()))
                .flatMap(d -> contracts.findByDocumentId(documentId)).orElseThrow(ContractNotFoundException::new);
    }

    public record Edit(long expectedVersion, List<Party> parties, Terms terms) {
        public ContractAggregate toAggregate(UUID documentId, PersistenceModels.Contract current) {
            List<ContractParty> mappedParties = parties == null ? List.of() : parties.stream()
                    .map(p -> new ContractParty(UUID.randomUUID(), documentId, p.role(), p.name())).toList();
            ContractTerms mappedTerms = terms == null ? null : terms.toDomain(documentId);
            return new ContractAggregate(new PersistenceModels.Contract(documentId, current.type(),
                    current.status(),
                    current.data(), current.version(), current.createdAt(), current.updatedAt(), current.archivedAt()),
                    mappedParties, mappedTerms);
        }
    }
    public record Party(String role, String name) { }
    public record Terms(LocalDate startDate, LocalDate endDate, Integer durationMonths, String renewalType,
                        String pricingModel, JsonNode electricityData) {
        ContractTerms toDomain(UUID documentId) {
            return new ContractTerms(documentId, startDate, endDate, durationMonths, renewalType, pricingModel,
                    electricityData == null ? null : new JsonValue(electricityData.toString()));
        }
    }
    public record Review(UUID documentId, String type, String status, long version, List<Party> parties, Terms terms) {
        static Review from(ContractAggregate aggregate, ObjectMapper json) {
            var c = aggregate.contract(); var t = aggregate.terms();
            JsonNode electricityData = null;
            if (t != null && t.electricityData() != null) {
                try { electricityData = json.readTree(t.electricityData().value()); }
                catch (Exception ignored) { /* persisted data was validated before creation */ }
            }
            Terms terms = t == null ? null : new Terms(t.startDate(), t.endDate(), t.contractDuration(),
                    t.renewalType() == null ? null : t.renewalType().value(),
                    t.pricingModel() == null ? null : t.pricingModel().value(), electricityData);
            return new Review(c.documentId(), c.type().value(), c.status().value(), c.version(),
                    aggregate.parties().stream().map(p -> new Party(p.role().value(), p.name())).toList(), terms);
        }
    }
    public static class ContractNotFoundException extends RuntimeException { }
    public static class NotDraftException extends RuntimeException { }
    public static class ConflictException extends RuntimeException { }
    public static class InvalidEditException extends RuntimeException { }
}
