package io.contractforge.backend.api;

import io.contractforge.backend.application.identity.IdentityResolutionService;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import io.contractforge.backend.application.upload.UploadIngestionService;
import io.contractforge.backend.application.upload.UploadProperties;
import io.contractforge.backend.application.upload.UploadService;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local bridge for the production S3/GuardDuty event not present in the current MVP runtime. */
@RestController
@Profile("local")
@RequestMapping("/v1/local/uploads")
final class LocalUploadCompletionController {
    private final UploadService uploads;
    private final IdentityResolutionService identities;
    private final UploadIngestionService ingestion;
    private final UploadProperties properties;

    LocalUploadCompletionController(UploadService uploads, IdentityResolutionService identities,
                                    UploadIngestionService ingestion, UploadProperties properties) {
        this.uploads = uploads;
        this.identities = identities;
        this.ingestion = ingestion;
        this.properties = properties;
    }

    @PostMapping("/{uploadId}/complete")
    UploadService.UploadStatusView complete(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                             @PathVariable UUID uploadId) {
        var user = identities.resolve(principal);
        uploads.status(user, uploadId);
        ingestion.process(new UploadIngestionService.ObjectCreatedCommand(uploadId,
                properties.objectKeyPrefix() + uploadId));
        return uploads.status(user, uploadId);
    }
}
