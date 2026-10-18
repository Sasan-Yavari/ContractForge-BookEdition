package io.contractforge.backend.application.identity;

import io.contractforge.backend.application.audit.AuditEventService;
import io.contractforge.backend.application.port.RepositoryPorts.UserIdentityRepository;
import io.contractforge.backend.application.port.RepositoryPorts.UserRepository;
import io.contractforge.backend.application.security.AuthenticatedApplicationUser;
import io.contractforge.backend.application.security.AuthenticatedPrincipal;
import io.contractforge.backend.domain.model.PersistenceModels.User;
import io.contractforge.backend.domain.model.PersistenceModels.UserIdentity;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the atomic first-use User and UserIdentity write. */
@Service
@Profile("!test")
public class IdentityProvisioningService {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final AuditEventService auditEventService;

    public IdentityProvisioningService(UserRepository userRepository,
                                       UserIdentityRepository userIdentityRepository,
                                       AuditEventService auditEventService) {
        this.userRepository = userRepository;
        this.userIdentityRepository = userIdentityRepository;
        this.auditEventService = auditEventService;
    }

    @Transactional
    public AuthenticatedApplicationUser provision(AuthenticatedPrincipal principal) {
        UUID userId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        userRepository.save(new User(userId, io.contractforge.backend.domain.model.UserStatus.ACTIVE, now, now));
        auditEventService.append(userId, AuditEventService.ResourceType.USER, userId, "CREATED");
        String email = TrustedEmailAddressNormalizer.normalize(principal.email(), principal.emailVerified()).orElse(null);
        userIdentityRepository.save(new UserIdentity(UUID.randomUUID(), userId,
                principal.provider(), principal.providerSubject(), email, now));
        return new AuthenticatedApplicationUser(userId);
    }
}
