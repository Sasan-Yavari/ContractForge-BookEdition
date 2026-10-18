package io.contractforge.backend.api;

import io.contractforge.backend.application.contract.ContractReviewService;
import io.contractforge.backend.application.contract.ContractReviewService.Edit;
import io.contractforge.backend.application.identity.IdentityResolutionService;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/contracts/{documentId}")
@Profile("!test")
public class ContractReviewController {
    private final ContractReviewService reviews;
    private final IdentityResolutionService identities;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public ContractReviewController(ContractReviewService reviews, IdentityResolutionService identities,
                                    com.fasterxml.jackson.databind.ObjectMapper json) {
        this.reviews = reviews; this.identities = identities; this.json = json;
    }

    @GetMapping
    public ReviewResponse get(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID documentId) {
        return ReviewResponse.from(reviews.read(identities.resolve(principal), documentId), json);
    }

    @PutMapping
    public ReviewResponse edit(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID documentId,
                               @RequestBody EditRequest request) {
        return ReviewResponse.from(reviews.edit(identities.resolve(principal), documentId, request.toEdit(json)), json);
    }

    @PatchMapping
    public ReviewResponse approve(@AuthenticationPrincipal AuthenticatedPrincipal principal, @PathVariable UUID documentId,
                                  @RequestBody ApprovalRequest request) {
        return ReviewResponse.from(reviews.approve(identities.resolve(principal), documentId, request.expectedVersion()), json);
    }

    record EditRequest(long expectedVersion, List<PartyRequest> parties, TermsRequest terms) {
        Edit toEdit(com.fasterxml.jackson.databind.ObjectMapper json) { return new Edit(expectedVersion, parties == null ? null : parties.stream().map(PartyRequest::toDomain).toList(),
                terms == null ? null : terms.toDomain(json)); }
    }
    record ApprovalRequest(long expectedVersion) { }
    record PartyRequest(String role, String name) {
        ContractReviewService.Party toDomain() { return new ContractReviewService.Party(role, name); }
    }
    record TermsRequest(LocalDate startDate, LocalDate endDate, Integer durationMonths, String renewalType,
                        String pricingModel, java.util.Map<String, Object> electricityData) {
        ContractReviewService.Terms toDomain(com.fasterxml.jackson.databind.ObjectMapper json) {
            try { return new ContractReviewService.Terms(startDate, endDate, durationMonths, renewalType, pricingModel,
                    electricityData == null ? null : json.readTree(json.writeValueAsString(electricityData))); }
            catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new ContractReviewService.InvalidEditException(); }
        }
    }
    record ReviewResponse(UUID documentId, String type, String status, long version, List<PartyRequest> parties,
                          TermsRequest terms) {
        static ReviewResponse from(ContractReviewService.Review review, com.fasterxml.jackson.databind.ObjectMapper json) {
            return new ReviewResponse(review.documentId(), review.type(), review.status(), review.version(),
                    review.parties().stream().map(p -> new PartyRequest(p.role(), p.name())).toList(),
                    review.terms() == null ? null : new TermsRequest(review.terms().startDate(), review.terms().endDate(),
                            review.terms().durationMonths(), review.terms().renewalType(), review.terms().pricingModel(),
                            review.terms().electricityData() == null ? null : json.convertValue(review.terms().electricityData(),
                                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { })));
        }
    }
}
