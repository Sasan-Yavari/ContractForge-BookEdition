package io.contractforge.backend.infrastructure.accountdeletion;

import io.contractforge.backend.application.accountdeletion.AccountDeletionProperties;
import io.contractforge.backend.application.accountdeletion.AccountDeletionObjectManifest;
import io.contractforge.backend.application.accountdeletion.AccountDeletionRepository;
import io.contractforge.backend.application.accountdeletion.AccountDeletionRepository.BeginResult;
import io.contractforge.backend.application.accountdeletion.AccountDeletionTombstone;
import io.contractforge.backend.application.port.RepositoryPorts.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Starts the existing durable account-deletion workflow against one isolated restored database. */
@Service
@Profile("restore-reconciliation")
class RestoreReconciliationBootstrap {
    private final AccountDeletionRepository deletions;
    private final UserRepository users;
    private final AccountDeletionProperties properties;
    private final AccountDeletionObjectManifest objectManifest;
    private final Clock clock;

    RestoreReconciliationBootstrap(AccountDeletionRepository deletions, UserRepository users,
                                   AccountDeletionProperties properties,
                                   AccountDeletionObjectManifest objectManifest, Clock clock) {
        this.deletions = deletions;
        this.users = users;
        this.properties = properties;
        this.objectManifest = objectManifest;
        this.clock = clock;
    }

    @Transactional
    public Optional<UUID> ensureJob(AccountDeletionTombstone tombstone) {
        UUID deletionId = tombstone.deletionId();
        var existing = deletions.find(deletionId);
        if (existing.isPresent()) {
            if (!existing.get().userId().equals(tombstone.userId())) {
                throw new IllegalStateException("Restored deletion job does not match its tombstone");
            }
            objectManifest.capture(deletionId, tombstone.userId());
            return Optional.of(deletionId);
        }

        var user = users.findById(tombstone.userId())
                .orElseThrow(() -> new IllegalStateException("Restored deletion account disappeared"));
        if (user.status() != io.contractforge.backend.domain.model.UserStatus.ACTIVE) {
            throw new IllegalStateException("Restored disabled account has no durable deletion job");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        BeginResult result = deletions.begin(tombstone.userId(), deletionId, now, now,
                        properties.uploadWriteGrace())
                .orElseThrow(() -> new IllegalStateException("Restored deletion account could not be fenced"));
        if (!result.deletionId().equals(deletionId)) {
            throw new IllegalStateException("Restored account belongs to another deletion job");
        }
        objectManifest.capture(deletionId, tombstone.userId());
        return Optional.of(deletionId);
    }
}
