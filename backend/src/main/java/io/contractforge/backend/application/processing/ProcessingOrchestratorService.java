package io.contractforge.backend.application.processing;

import io.contractforge.backend.application.DocumentProcessingService;
import io.contractforge.backend.application.accountdeletion.AccountDeletionInProgressException;
import io.contractforge.backend.application.port.ProcessingPublisherPort.ProcessingMessage;
import io.contractforge.backend.application.port.RepositoryPorts.DocumentRepository;
import io.contractforge.backend.domain.model.PersistenceModels.Document;
import io.contractforge.backend.domain.model.PersistenceModels.ProcessingAttempt;
import io.contractforge.backend.domain.model.PersistenceModels.ContractAggregate;
import io.contractforge.backend.domain.model.DocumentStatus;
import io.contractforge.backend.domain.model.ProcessingAttemptStatus;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns application-attempt lifecycle and keeps SQS delivery retries out of persistence. */
@Service
@Profile("!test")
public class ProcessingOrchestratorService {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessingOrchestratorService.class);
    private final DocumentRepository documents;
    private final ProcessingAttemptLifecycleService lifecycle;
    private final ProcessingWorkPort work;
    private final DocumentProcessingService documentProcessing;

    public ProcessingOrchestratorService(DocumentRepository documents, ProcessingAttemptLifecycleService lifecycle,
                                         ProcessingWorkPort work, DocumentProcessingService documentProcessing) {
        this.documents = documents;
        this.lifecycle = lifecycle;
        this.work = work;
        this.documentProcessing = documentProcessing;
    }

    @Autowired
    public ProcessingOrchestratorService(DocumentRepository documents, ProcessingAttemptLifecycleService lifecycle,
                                         org.springframework.beans.factory.ObjectProvider<ProcessingWorkPort> work,
                                         DocumentProcessingService documentProcessing) {
        this.documents = documents;
        this.lifecycle = lifecycle;
        this.work = work.getIfAvailable();
        this.documentProcessing = documentProcessing;
    }

    public Outcome consume(String body) {
        return consume(ProcessingMessageCodec.decode(body));
    }

    public Outcome consume(ProcessingMessage message) {
        ProcessingAttempt attempt;
        try {
            attempt = lifecycle.begin(message.documentId());
        } catch (AccountDeletionInProgressException deletionFence) {
            return Outcome.ALREADY_TERMINAL;
        }
        if (attempt == null) {
            return documents.findById(message.documentId())
                    .filter(document -> document.status() == DocumentStatus.PROCESSING)
                    .isPresent() ? Outcome.IN_PROGRESS : Outcome.ALREADY_TERMINAL;
        }
        LOG.atInfo()
                .addKeyValue("event_type", "document_processing_started")
                .addKeyValue("attempt_number", attempt.attemptNumber())
                .log("document_processing_started");
        try {
            Document document = documents.findById(message.documentId()).orElseThrow(StaleProcessingWorkException::new);
            if (work == null) {
                throw new ProcessingUnavailableException();
            }
            ContractAggregate result = work.process(document);
            if (result == null) {
                throw new IllegalStateException("Processing returned no contract result");
            }
            lifecycle.completeSuccessfully(attempt.id(), message.documentId(), result);
            LOG.atInfo()
                    .addKeyValue("event_type", "document_processing_succeeded")
                    .addKeyValue("attempt_number", attempt.attemptNumber())
                    .log("document_processing_succeeded");
            return Outcome.COMPLETED;
        } catch (RuntimeException failure) {
            if (failure instanceof AccountDeletionInProgressException
                    || failure instanceof StaleProcessingWorkException
                    || failure instanceof ProcessingAttemptLifecycleService.StaleAttemptCompletionException) {
                return Outcome.ALREADY_TERMINAL;
            }
            try {
                lifecycle.complete(attempt.id(), ProcessingAttemptStatus.FAILED, safeErrorCode(failure));
            } catch (AccountDeletionInProgressException | ProcessingAttemptLifecycleService.StaleAttemptCompletionException deleted) {
                return Outcome.ALREADY_TERMINAL;
            }
            LOG.atWarn()
                    .addKeyValue("event_type", "document_processing_failed")
                    .addKeyValue("attempt_number", attempt.attemptNumber())
                    .addKeyValue("failure_code", safeErrorCode(failure))
                    .log("document_processing_failed");
            throw failure;
        }
    }

    public enum Outcome {
        COMPLETED, ALREADY_TERMINAL, IN_PROGRESS
    }

    private String safeErrorCode(RuntimeException failure) {
        if (failure instanceof RetryableProcessingException) {
            return ProcessingErrorCode.RETRYABLE_PROCESSING_FAILURE;
        }
        if (failure instanceof PermanentProcessingException permanent && permanent.errorCode() != null) {
            return permanent.errorCode();
        }
        return ProcessingErrorCode.UNCLASSIFIED_PROCESSING_FAILURE;
    }

    public static class StaleProcessingWorkException extends RuntimeException { }
    public static class ProcessingUnavailableException extends RuntimeException { }
}
