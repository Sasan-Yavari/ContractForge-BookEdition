package io.contractforge.backend.api;

import io.contractforge.backend.application.document.OriginalDocumentAccessService;
import io.contractforge.backend.application.identity.IdentityResolutionService;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/documents/{documentId}/original")
@Profile("!test")
public class OriginalDocumentController {
    private final OriginalDocumentAccessService access;
    private final IdentityResolutionService identities;

    public OriginalDocumentController(OriginalDocumentAccessService access, IdentityResolutionService identities) {
        this.access = access;
        this.identities = identities;
    }

    @GetMapping
    public ResponseEntity<OriginalDocumentResponse> get(@AuthenticationPrincipal AuthenticatedPrincipal principal,
                                                        @PathVariable UUID documentId) {
        var target = access.request(identities.resolve(principal), documentId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new OriginalDocumentResponse(target.url(), target.expiresAt()));
    }

    public record OriginalDocumentResponse(URI url, OffsetDateTime expiresAt) { }
}
