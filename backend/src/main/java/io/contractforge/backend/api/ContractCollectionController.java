package io.contractforge.backend.api;

import io.contractforge.backend.application.contract.ContractCollectionService;
import io.contractforge.backend.application.contract.ContractCollectionService.ContractCollectionItem;
import io.contractforge.backend.application.identity.IdentityResolutionService;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import io.contractforge.backend.domain.model.ContractPricingModel;
import io.contractforge.backend.domain.model.ContractType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner-scoped read endpoint for the user's complete contract collection. */
@RestController
@RequestMapping("/v1/contracts")
@Profile("!test")
public final class ContractCollectionController {
    private final ContractCollectionService collection;
    private final IdentityResolutionService identities;

    public ContractCollectionController(ContractCollectionService collection, IdentityResolutionService identities) {
        this.collection = collection;
        this.identities = identities;
    }

    @GetMapping
    public List<ContractCollectionResponse> list(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        return collection.list(identities.resolve(principal)).stream().map(ContractCollectionResponse::from).toList();
    }

    public record ContractCollectionResponse(UUID id, ContractType contractType, String status,
                                             String sourceFilename, OffsetDateTime createdAt,
                                             String provider, ContractPricingModel pricingModel,
                                             LocalDate endDate) {
        static ContractCollectionResponse from(ContractCollectionItem item) {
            return new ContractCollectionResponse(item.id(), item.type(), item.status().name(),
                    item.sourceFilename(), item.createdAt(), item.provider(), item.pricingModel(), item.endDate());
        }
    }
}
