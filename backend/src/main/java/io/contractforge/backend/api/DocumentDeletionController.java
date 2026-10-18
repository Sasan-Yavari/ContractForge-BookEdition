package io.contractforge.backend.api;

import io.contractforge.backend.application.document.DocumentDeletionService;
import io.contractforge.backend.application.document.DocumentStatusService;
import io.contractforge.backend.application.document.DocumentStatusService.DocumentStatusView;
import io.contractforge.backend.application.identity.IdentityResolutionService;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import io.contractforge.backend.domain.model.ContractType;
import io.contractforge.backend.domain.model.DocumentStatus;
import io.contractforge.backend.domain.model.ProcessingFailureCategory;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/documents")
@Profile("!test")
public final class DocumentDeletionController {
    private final DocumentDeletionService deletions;
    private final DocumentStatusService statuses;
    private final IdentityResolutionService identities;

    public DocumentDeletionController(DocumentDeletionService deletions, DocumentStatusService statuses,
                                       IdentityResolutionService identities) {
        this.deletions = deletions; this.statuses = statuses;
        this.identities = identities;
    }

    @GetMapping("/{documentId}")
    public StatusResponse status(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                 @PathVariable UUID documentId) {
        DocumentStatusView status = statuses.status(identities.resolve(principal), documentId);
        return new StatusResponse(status.documentId(), status.status(), status.contractType(),
                status.originalFilename(), status.failureCategory());
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                       @PathVariable UUID documentId) {
        deletions.delete(identities.resolve(principal), documentId);
        return ResponseEntity.noContent().build();
    }

    public record StatusResponse(UUID documentId, DocumentStatus status, ContractType contractType,
                                 String originalFilename, ProcessingFailureCategory failureCategory) { }
}
