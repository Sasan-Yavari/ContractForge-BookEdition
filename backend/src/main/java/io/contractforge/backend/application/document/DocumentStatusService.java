package io.contractforge.backend.application.document;

import io.contractforge.backend.application.port.RepositoryPorts.DocumentRepository;
import io.contractforge.backend.application.port.RepositoryPorts.ProcessingAttemptRepository;
import io.contractforge.backend.application.processing.ProcessingErrorCode;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import io.contractforge.backend.domain.model.PersistenceModels.ProcessingAttempt;
import io.contractforge.backend.domain.model.ProcessingAttemptStatus;
import io.contractforge.backend.domain.model.ProcessingFailureCategory;
import io.contractforge.backend.domain.model.PersistenceModels.Document;
import io.contractforge.backend.domain.model.DocumentStatus;
import io.contractforge.backend.domain.model.ContractType;
import java.util.UUID;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Exposes the owner's minimal, authoritative document-processing state. */
@Service
@Profile("!test")
public class DocumentStatusService {
    private static final Set<String> DETERMINISTIC_DOCUMENT_ERRORS = Set.of(
            ProcessingErrorCode.MALFORMED_DOCUMENT,
            ProcessingErrorCode.LEGACY_MALFORMED_DOCUMENT_EXCEPTION,
            ProcessingErrorCode.RESOURCE_LIMIT_INPUT_BYTES,
            ProcessingErrorCode.RESOURCE_LIMIT_EMPTY_CONTENT,
            ProcessingErrorCode.RESOURCE_LIMIT_PAGE_COUNT,
            ProcessingErrorCode.RESOURCE_LIMIT_OUTPUT_CHARACTERS,
            ProcessingErrorCode.RESOURCE_LIMIT_LLM_INPUT_CHARACTERS);
    private final DocumentRepository documents;
    private final ProcessingAttemptRepository attempts;

    public DocumentStatusService(DocumentRepository documents, ProcessingAttemptRepository attempts) {
        this.documents = documents;
        this.attempts = attempts;
    }

    @Transactional(readOnly = true)
    public DocumentStatusView status(AuthenticatedApplicationUser user, UUID documentId) {
        if (user == null || documentId == null) {
            throw new DocumentNotFoundException();
        }
        Document document = documents.findById(documentId)
                .filter(candidate -> user.userId().equals(candidate.userId()))
                .orElseThrow(DocumentNotFoundException::new);
        ProcessingFailureCategory failureCategory = document.status() == DocumentStatus.FAILED
                ? attempts.findLatestByDocumentId(document.id()).map(this::failureCategory)
                        .orElse(ProcessingFailureCategory.UNKNOWN)
                : null;
        return new DocumentStatusView(document.id(), document.status(), document.contractType(),
                document.filename(), failureCategory);
    }

    private ProcessingFailureCategory failureCategory(ProcessingAttempt attempt) {
        if (attempt.status() != ProcessingAttemptStatus.FAILED) return ProcessingFailureCategory.UNKNOWN;
        String code = attempt.errorCode();
        if (ProcessingErrorCode.RETRYABLE_PROCESSING_FAILURE.equals(code)
                || ProcessingErrorCode.LEGACY_RETRYABLE_PROCESSING_EXCEPTION.equals(code)) {
            return ProcessingFailureCategory.RETRYABLE;
        }
        if (code != null && DETERMINISTIC_DOCUMENT_ERRORS.contains(code)) {
            return ProcessingFailureCategory.DETERMINISTIC_DOCUMENT;
        }
        return ProcessingFailureCategory.UNKNOWN;
    }

    public record DocumentStatusView(UUID documentId, DocumentStatus status, ContractType contractType,
                                     String originalFilename, ProcessingFailureCategory failureCategory) { }

    public static final class DocumentNotFoundException extends RuntimeException { }
}
