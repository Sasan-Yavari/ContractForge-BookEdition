package io.contractforge.backend.api;

import io.contractforge.backend.application.upload.UploadService.UploadNotFoundException;
import io.contractforge.backend.application.upload.UploadService.UploadValidationException;
import io.contractforge.backend.application.accountdeletion.AccountDeletionUnavailableException;
import io.contractforge.backend.application.accountdeletion.AccountDeletionDisablePendingException;
import io.contractforge.backend.application.contract.ContractReviewService;
import io.contractforge.backend.application.contract.ContractOverviewService;
import io.contractforge.backend.application.document.DocumentDeletionService;
import io.contractforge.backend.application.document.DocumentStatusService;
import io.contractforge.backend.application.document.OriginalDocumentAccessService;
import io.contractforge.backend.application.identity.ApplicationAccessDeniedException;
import io.contractforge.backend.application.accountdeletion.AccountDeletionInProgressException;
import io.contractforge.backend.application.port.ObjectStoragePort;
import io.contractforge.backend.application.upload.UploadInitiationRateLimitExceededException;
import io.contractforge.backend.application.upload.UploadRateLimitCapacityExceededException;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class UploadExceptionHandler {
    private static final String UPLOAD_RATE_LIMIT_EXCEEDED = "UPLOAD_RATE_LIMIT_EXCEEDED";
    private static final String UPLOAD_INITIATION_TEMPORARILY_UNAVAILABLE = "UPLOAD_INITIATION_TEMPORARILY_UNAVAILABLE";
    private static final String ACCOUNT_DELETION_DISABLE_PENDING = "ACCOUNT_DELETION_DISABLE_PENDING";

    @ExceptionHandler(UploadInitiationRateLimitExceededException.class)
    ResponseEntity<Map<String, String>> uploadInitiationRateLimited(
            UploadInitiationRateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(Map.of("error", UPLOAD_RATE_LIMIT_EXCEEDED));
    }

    @ExceptionHandler(UploadRateLimitCapacityExceededException.class)
    ResponseEntity<Map<String, String>> uploadInitiationCapacityUnavailable(
            UploadRateLimitCapacityExceededException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(Map.of("error", UPLOAD_INITIATION_TEMPORARILY_UNAVAILABLE));
    }

    @ExceptionHandler(UploadValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalidUpload() { return Map.of("error", "INVALID_UPLOAD_REQUEST"); }

    @ExceptionHandler({ApplicationAccessDeniedException.class, AccountDeletionInProgressException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)
    Map<String, String> inactiveApplicationAccount() { return Map.of("error", "APPLICATION_ACCESS_DENIED"); }

    @ExceptionHandler(AccountDeletionUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    Map<String, String> accountDeletionUnavailable() { return Map.of("error", "ACCOUNT_DELETION_UNAVAILABLE"); }

    @ExceptionHandler(AccountDeletionDisablePendingException.class)
    ResponseEntity<Map<String, String>> accountDeletionDisablePending(AccountDeletionDisablePendingException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(exception.retryAfterSeconds()))
                .body(Map.of("error", ACCOUNT_DELETION_DISABLE_PENDING));
    }

    @ExceptionHandler(UploadNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> missingUpload() { return Map.of("error", "UPLOAD_NOT_FOUND"); }

    @ExceptionHandler({ContractReviewService.ContractNotFoundException.class, ContractReviewService.NotDraftException.class,
            ContractOverviewService.ContractOverviewNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> missingContract() { return Map.of("error", "CONTRACT_NOT_FOUND"); }

    @ExceptionHandler(DocumentDeletionService.DocumentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> missingDocument() { return Map.of("error", "DOCUMENT_NOT_FOUND"); }

    @ExceptionHandler(DocumentStatusService.DocumentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> missingDocumentStatus() { return Map.of("error", "DOCUMENT_NOT_FOUND"); }

    @ExceptionHandler({OriginalDocumentAccessService.OriginalDocumentNotFoundException.class,
            ObjectStoragePort.SourceObjectMissingException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> unavailableOriginalDocument() { return Map.of("error", "ORIGINAL_DOCUMENT_NOT_FOUND"); }

    @ExceptionHandler(ObjectStoragePort.StorageUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    Map<String, String> originalDocumentStorageUnavailable() { return Map.of("error", "ORIGINAL_DOCUMENT_UNAVAILABLE"); }

    @ExceptionHandler(ContractReviewService.InvalidEditException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalidContractEdit() { return Map.of("error", "INVALID_CONTRACT_EDIT"); }

    @ExceptionHandler(ContractReviewService.ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> contractConflict() { return Map.of("error", "CONTRACT_VERSION_CONFLICT"); }
}
