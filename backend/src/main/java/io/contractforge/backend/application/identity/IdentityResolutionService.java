package io.contractforge.backend.application.identity;

import io.contractforge.backend.application.port.RepositoryPorts.UserIdentityRepository;
import io.contractforge.backend.application.port.RepositoryPorts.UserRepository;
import io.contractforge.backend.application.accountdeletion.AccountDeletionTombstonePort;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import io.contractforge.backend.domain.model.PersistenceModels.User;
import io.contractforge.backend.domain.model.PersistenceModels.UserIdentity;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Resolves external identity once, then explicitly propagates the application user ID. */
@Service
@Profile("!test")
public class IdentityResolutionService {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final IdentityProvisioningService provisioningService;
    private final AccountDeletionTombstonePort tombstones;

    public IdentityResolutionService(UserRepository userRepository,
                                    UserIdentityRepository userIdentityRepository,
                                    @Qualifier("identityProvisioningService") IdentityProvisioningService provisioningService,
                                    AccountDeletionTombstonePort tombstones) {
        this.userRepository = userRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.provisioningService = provisioningService;
        this.tombstones = tombstones;
    }

    public AuthenticatedApplicationUser resolve(AuthenticatedPrincipal principal) {
        validate(principal);
        Optional<UserIdentity> existing = userIdentityRepository.findByProviderSubject(
                principal.provider(), principal.providerSubject());
        if (existing.isPresent()) {
            return resolveUser(existing.get(), principal);
        }
        if (tombstones.blocksProvisioning(principal.provider(), principal.providerSubject())) {
            throw new ApplicationAccessDeniedException();
        }
        try {
            return provisioningService.provision(principal);
        } catch (ProviderSubjectAlreadyExistsException race) {
            return userIdentityRepository.findByProviderSubject(principal.provider(), principal.providerSubject())
                    .map(identity -> resolveUser(identity, principal))
                    .orElseThrow(() -> race);
        }
    }

    private AuthenticatedApplicationUser resolveUser(UserIdentity identity, AuthenticatedPrincipal principal) {
        User user = userRepository.findById(identity.userId())
                .orElseThrow(() -> new IllegalStateException("Identity has no application user"));
        if (user.status() != io.contractforge.backend.domain.model.UserStatus.ACTIVE) {
            throw new ApplicationAccessDeniedException();
        }
        String trustedEmail = TrustedEmailAddressNormalizer.normalize(principal.email(), principal.emailVerified())
                .orElse(null);
        if (!Objects.equals(identity.email(), trustedEmail)) {
            userIdentityRepository.updateTrustedEmail(identity.id(), trustedEmail);
        }
        return new AuthenticatedApplicationUser(user.id());
    }

    private void validate(AuthenticatedPrincipal principal) {
        if (principal == null || principal.provider() == null || principal.provider().isBlank()
                || principal.providerSubject() == null || principal.providerSubject().isBlank()) {
            throw new IllegalArgumentException("Authenticated external identity is incomplete");
        }
    }
}
