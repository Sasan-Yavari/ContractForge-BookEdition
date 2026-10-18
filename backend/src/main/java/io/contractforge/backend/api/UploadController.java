package io.contractforge.backend.api;

import io.contractforge.backend.application.identity.IdentityResolutionService;
import io.contractforge.backend.application.port.ObjectStoragePort.UploadInstructions;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import io.contractforge.backend.application.upload.UploadService;
import io.contractforge.backend.application.upload.UploadService.InitiatedUpload;
import io.contractforge.backend.application.upload.UploadService.UploadStatusView;
import io.contractforge.backend.domain.model.ContractType;
import java.time.OffsetDateTime;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

@RestController
@RequestMapping("/v1/uploads")
@Profile("!test")
public class UploadController {
    private final UploadService uploads;
    private final IdentityResolutionService identities;

    public UploadController(UploadService uploads, IdentityResolutionService identities) {
        this.uploads = uploads;
        this.identities = identities;
    }

    @PostMapping
    public StartUploadResponse start(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                     @RequestBody StartUploadRequest request) {
        if (request == null) throw new UploadService.UploadValidationException();
        InitiatedUpload initiated = uploads.initiate(identities.resolve(principal),
                new UploadService.StartUpload(request.filename(), request.contentType(), request.size(),
                        parseContractType(request.contractType())));
        UploadInstructions target = initiated.instructions();
        return new StartUploadResponse(initiated.upload().id(), initiated.upload().status().value(),
                initiated.upload().contractType(), initiated.upload().originalFilename(), initiated.upload().expiresAt(),
                new UploadTarget("PUT", target.url(), target.headers(), initiated.upload().expiresAt()));
    }

    @GetMapping("/{id}")
    public StatusResponse status(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID id) {
        UploadStatusView status = uploads.status(identities.resolve(principal), id);
        return new StatusResponse(status.id(), status.status(), status.contractType(), status.originalFilename(), status.expiresAt());
    }

    private ContractType parseContractType(String value) {
        try {
            return ContractType.fromValue(value);
        } catch (IllegalArgumentException | NullPointerException invalidType) {
            throw new UploadService.UploadValidationException();
        }
    }

    public record StartUploadRequest(String filename, String contentType, long size, String contractType) { }
    public record StartUploadResponse(UUID uploadId, String status, ContractType contractType, String originalFilename,
                                      OffsetDateTime expiresAt, UploadTarget upload) { }
    public record UploadTarget(String method, URI url, Map<String, String> headers, OffsetDateTime expiresAt) { }
    public record StatusResponse(UUID uploadId, String status, ContractType contractType, String originalFilename,
                                 OffsetDateTime expiresAt) { }
}
