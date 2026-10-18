package io.contractforge.backend.application.processing;

import io.contractforge.backend.application.DocumentProcessingService;
import io.contractforge.backend.application.port.ActiveAccountWritePort;
import io.contractforge.backend.application.accountdeletion.AccountDeletionInProgressException;
import io.contractforge.backend.application.port.RepositoryPorts.DocumentRepository;
import io.contractforge.backend.application.port.RepositoryPorts.ProcessingAttemptRepository;
import io.contractforge.backend.domain.model.PersistenceModels.Document;
import io.contractforge.backend.domain.model.PersistenceModels.ProcessingAttempt;
import io.contractforge.backend.domain.model.PersistenceModels.ContractAggregate;
import io.contractforge.backend.domain.model.DocumentStatus;
import io.contractforge.backend.domain.model.ProcessingAttemptStatus;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** Transactional database boundary for locking a Document and allocating its next attempt. */
@Service
@Profile("!test")
public class ProcessingAttemptLifecycleService {
    private final DocumentRepository documents;
    private final ProcessingAttemptRepository attempts;
    private final DocumentProcessingService documentProcessing;
    private final Duration staleAfter;
    private final ActiveAccountWritePort activeAccounts;

    public ProcessingAttemptLifecycleService(DocumentRepository documents, ProcessingAttemptRepository attempts,
                                             DocumentProcessingService documentProcessing,
                                             Duration staleAfter) {
        this(documents, attempts, documentProcessing, staleAfter, null);
    }

    @Autowired
    public ProcessingAttemptLifecycleService(DocumentRepository documents, ProcessingAttemptRepository attempts,
                                             DocumentProcessingService documentProcessing,
                                             @Value("${contractforge.processing.stale-attempt-after:PT1H}") Duration staleAfter,
                                             ActiveAccountWritePort activeAccounts) {
        this.documents = documents;
        this.attempts = attempts;
        this.documentProcessing = documentProcessing;
        this.staleAfter = staleAfter;
        this.activeAccounts = activeAccounts;
    }

    public ProcessingAttemptLifecycleService(DocumentRepository documents, ProcessingAttemptRepository attempts,
                                             DocumentProcessingService documentProcessing) {
        this(documents, attempts, documentProcessing, Duration.ofHours(1));
    }

    @Transactional
    public ProcessingAttempt begin(UUID documentId) {
        Document document = documents.findByIdForUpdate(documentId).orElse(null);
        if (document == null || document.status() != DocumentStatus.PROCESSING) {
            return null;
        }
        requireActive(document);
        ProcessingAttempt running = attempts.findRunningByDocumentId(documentId).orElse(null);
        if (running != null && running.startedAt().isAfter(OffsetDateTime.now().minus(staleAfter))) {
            return null;
        }
        if (running != null) {
            attempts.finalize(running.id(), ProcessingAttemptStatus.FAILED, ProcessingErrorCode.STALE_ATTEMPT_RECOVERED);
        }
        int nextNumber = attempts.findLatestByDocumentId(documentId).map(a -> a.attemptNumber() + 1).orElse(1);
        ProcessingAttempt attempt = new ProcessingAttempt(UUID.randomUUID(), documentId, nextNumber, ProcessingAttemptStatus.RUNNING,
                OffsetDateTime.now(), null, null);
        attempts.save(attempt);
        return attempt;
    }

    @Transactional
    public void complete(UUID attemptId, ProcessingAttemptStatus status, String errorCode) {
        ProcessingAttempt attempt = attempts.findById(attemptId).orElse(null);
        if (attempt == null) return;
        Document document = documents.findById(attempt.documentId()).orElse(null);
        if (document == null) throw new StaleAttemptCompletionException();
        requireActive(document);
        attempts.finalize(attemptId, status, errorCode);
    }

    @Transactional
    public void completeSuccessfully(UUID attemptId, UUID documentId, ContractAggregate result) {
        Document document = documents.findByIdForUpdate(documentId).orElse(null);
        if (document == null
                || attempts.findById(attemptId).filter(a -> a.documentId().equals(documentId)
                        && a.status() == ProcessingAttemptStatus.RUNNING).isEmpty()) {
            throw new StaleAttemptCompletionException();
        }
        requireActive(document);
        documentProcessing.completeSuccessfully(documentId, result);
        attempts.finalize(attemptId, ProcessingAttemptStatus.SUCCEEDED, null);
    }

    @Transactional
    public void failRunning(UUID documentId, String errorCode) {
        Document document = documents.findByIdForUpdate(documentId).orElse(null);
        if (document == null) return;
        requireActive(document);
        attempts.finalizeRunningByDocumentId(documentId, errorCode);
    }

    public static class StaleAttemptCompletionException extends RuntimeException { }

    private void requireActive(Document document) {
        if (activeAccounts != null && !activeAccounts.lockActiveUser(document.userId())) {
            throw new AccountDeletionInProgressException();
        }
    }
}
