package io.contractforge.backend.application.document;

import io.contractforge.backend.application.audit.AuditEventService;
import io.contractforge.backend.application.port.ObjectStoragePort;
import io.contractforge.backend.application.port.RepositoryPorts.DocumentRepository;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issues a short-lived browser read target only after resolving the authenticated owner. */
@Service
@Profile("!test")
public class OriginalDocumentAccessService {
    private static final String APPLICATION_PDF_CONTENT_TYPE = "application/pdf";
    private static final Duration READ_TARGET_VALIDITY = Duration.ofMinutes(5);

    private final DocumentRepository documents;
    private final ObjectStoragePort storage;
    private final AuditEventService audit;
    private final Clock clock;

    public OriginalDocumentAccessService(DocumentRepository documents, ObjectStoragePort storage,
                                         AuditEventService audit, Clock clock) {
        this.documents = documents;
        this.storage = storage;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public AccessTarget request(AuthenticatedApplicationUser user, UUID documentId) {
        if (user == null || user.userId() == null || documentId == null) {
            throw new OriginalDocumentNotFoundException();
        }
        var document = documents.findById(documentId)
                .filter(candidate -> user.userId().equals(candidate.userId())
                        && APPLICATION_PDF_CONTENT_TYPE.equals(candidate.mimeType()))
                .orElseThrow(OriginalDocumentNotFoundException::new);
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(READ_TARGET_VALIDITY);
        URI url = storage.presignGet(document.storageKey(), READ_TARGET_VALIDITY).url();
        audit.append(user.userId(), AuditEventService.ResourceType.DOCUMENT, documentId,
                "ORIGINAL_ACCESS_ISSUED");
        return new AccessTarget(url, expiresAt);
    }

    public record AccessTarget(URI url, OffsetDateTime expiresAt) { }

    public static final class OriginalDocumentNotFoundException extends RuntimeException { }
}
