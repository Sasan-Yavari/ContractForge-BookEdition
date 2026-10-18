package io.contractforge.backend.application.processing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.contractforge.backend.application.extraction.ContractExtractionPort;
import io.contractforge.backend.application.extraction.ContractExtractionAccountingProperties;
import io.contractforge.backend.application.extraction.DocumentTextExtractionService;
import io.contractforge.backend.application.extraction.ElectricityContractCandidateValidator;
import io.contractforge.backend.application.extraction.ModelInputPreparationService;
import io.contractforge.backend.application.accounting.CostCalculation;
import io.contractforge.backend.application.accounting.ProviderCostCalculator;
import io.contractforge.backend.domain.model.PersistenceModels.Contract;
import io.contractforge.backend.domain.model.PersistenceModels.ContractAggregate;
import io.contractforge.backend.domain.model.PersistenceModels.ContractParty;
import io.contractforge.backend.domain.model.PersistenceModels.ContractTerms;
import io.contractforge.backend.domain.model.PersistenceModels.Document;
import io.contractforge.backend.domain.model.PersistenceModels.JsonValue;
import io.contractforge.backend.domain.model.ContractStatus;
import io.contractforge.backend.domain.model.ContractType;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Connects the existing processing work seam to bounded text extraction and validated electricity data. */
@Service
@Profile("!test")
public final class ElectricityContractProcessingWorkService implements ProcessingWorkPort {
    private static final ContractStatus DRAFT_STATUS = ContractStatus.DRAFT;
    private static final String LLM_OPERATION = "LLM_EXTRACTION";
    private static final String BEDROCK_PROVIDER = "aws";
    private final DocumentTextExtractionService textExtraction;
    private final ModelInputPreparationService modelInputPreparation;
    private final ContractExtractionPort extraction;
    private final ElectricityContractCandidateValidator validator;
    private final io.contractforge.backend.application.accounting.AccountingPort accounting;
    private final ContractExtractionAccountingProperties accountingProperties;
    private final ProviderCostCalculator costCalculator;
    private final ObjectMapper json;

    @Autowired
    public ElectricityContractProcessingWorkService(DocumentTextExtractionService textExtraction,
                                                     ModelInputPreparationService modelInputPreparation,
                                                     ContractExtractionPort extraction,
                                                     ElectricityContractCandidateValidator validator,
                                                     io.contractforge.backend.application.accounting.AccountingPort accounting,
                                                     ContractExtractionAccountingProperties accountingProperties,
                                                     ProviderCostCalculator costCalculator, ObjectMapper json) {
        this.textExtraction = textExtraction;
        this.modelInputPreparation = modelInputPreparation;
        this.extraction = extraction;
        this.validator = validator;
        this.accounting = accounting;
        this.accountingProperties = accountingProperties;
        this.costCalculator = costCalculator;
        this.json = json;
    }

    public ElectricityContractProcessingWorkService(DocumentTextExtractionService textExtraction,
                                                     ModelInputPreparationService modelInputPreparation,
                                                     ContractExtractionPort extraction,
                                                     ElectricityContractCandidateValidator validator,
                                                     io.contractforge.backend.application.accounting.AccountingPort accounting,
                                                     ContractExtractionAccountingProperties accountingProperties) {
        this(textExtraction, modelInputPreparation, extraction, validator, accounting, accountingProperties,
                (provider, service, dimension, usage, occurredAt) -> CostCalculation.unknown("USD"), new ObjectMapper());
    }

    @Override
    public ContractAggregate process(Document document) {
        ContractExtractionPort.ExtractionResult extractionResult;
        InvocationState invocation = new InvocationState();
        try {
            DocumentTextExtractionService.ExtractionResult text = textExtraction.extract(document.id());
            extractionResult = extraction.extract(modelInputPreparation.prepare(text.text()), invocation::record);
        } catch (ContractExtractionPort.RetryableExtractionException failure) {
            if (invocation.invoked) publishUsage(document, unknownUsage());
            throw new RetryableProcessingException(failure);
        } catch (ContractExtractionPort.PermanentExtractionException failure) {
            if (invocation.invoked) publishUsage(document, unknownUsage());
            throw new PermanentProcessingException(failure);
        } catch (RuntimeException failure) {
            ProcessingFailureDiagnostics.record(ProcessingFailureDiagnostics.Stage.OTHER_RUNTIME, failure);
            throw failure;
        }
        JsonValue usageData;
        ContractExtractionPort.Candidate candidate;
        try {
            usageData = extractionResult.usageData();
            candidate = extractionResult.candidate();
        } catch (RuntimeException failure) {
            ProcessingFailureDiagnostics.record(ProcessingFailureDiagnostics.Stage.OTHER_RUNTIME, failure);
            throw failure;
        }
        publishUsage(document, usageData);
        try {
            validator.validate(candidate);
        } catch (ElectricityContractCandidateValidator.InvalidCandidateException failure) {
            ProcessingFailureDiagnostics.record(ProcessingFailureDiagnostics.Stage.BUSINESS_VALIDATION, failure);
            throw new PermanentProcessingException(failure);
        } catch (RuntimeException failure) {
            ProcessingFailureDiagnostics.record(ProcessingFailureDiagnostics.Stage.OTHER_RUNTIME, failure);
            throw failure;
        }
        return toAggregate(document.id(), document.contractType(), candidate);
    }

    private void publishUsage(Document document, io.contractforge.backend.domain.model.PersistenceModels.JsonValue usageData) {
        try {
            OffsetDateTime occurredAt = OffsetDateTime.now();
            CostCalculation cost = costCalculator.calculate("aws", accountingProperties.service(), accountingProperties.service(),
                    usageData, occurredAt);
            accounting.publish(new io.contractforge.backend.application.accounting.AccountingPort.UsageEvent(
                    UUID.randomUUID(), document.userId(), document.id(), LLM_OPERATION, BEDROCK_PROVIDER,
                    accountingProperties.service(), usageData, cost.amount(), cost.currency(), occurredAt,
                    cost.status(), cost.pricingVersion()));
        } catch (RuntimeException failure) {
            ProcessingFailureDiagnostics.record(ProcessingFailureDiagnostics.Stage.OTHER_RUNTIME, failure);
            throw failure;
        }
    }

    private JsonValue unknownUsage() {
        try {
            return new JsonValue(json.writeValueAsString(new ProviderUsage(null, null, null)));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Unknown provider usage could not be serialized", failure);
        }
    }

    private record ProviderUsage(Integer input_tokens, Integer output_tokens, Integer total_tokens) { }

    private record ContractData(String schema_version) { }

    private static final class InvocationState {
        private boolean invoked;
        private void record() { invoked = true; }
    }

    ContractAggregate toAggregate(UUID documentId, ContractType contractType,
                                  ContractExtractionPort.Candidate candidate) {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            Contract contract = new Contract(documentId, contractType, DRAFT_STATUS,
                    serialize(new ContractData(ContractExtractionPort.CURRENT_SCHEMA_VERSION)),
                    0L, now, now, null);
            var parties = candidate.parties().stream()
                    .map(p -> new ContractParty(UUID.randomUUID(), documentId, p.role(), p.name())).toList();
            ContractTerms terms = new ContractTerms(documentId, candidate.startDate(), candidate.endDate(),
                    candidate.durationMonths(), candidate.renewalType(), candidate.pricingModel(),
                    candidate.electricityData());
            return new ContractAggregate(contract, parties, terms);
        } catch (RuntimeException failure) {
            ProcessingFailureDiagnostics.record(ProcessingFailureDiagnostics.Stage.AGGREGATE_CONSTRUCTION,
                    failure);
            throw failure;
        }
    }

    private JsonValue serialize(Object value) {
        try {
            return new JsonValue(json.writeValueAsString(value));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Processing data could not be serialized", failure);
        }
    }
}
