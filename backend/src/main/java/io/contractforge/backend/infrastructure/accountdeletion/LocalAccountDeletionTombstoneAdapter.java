package io.contractforge.backend.infrastructure.accountdeletion;

import io.contractforge.backend.application.accountdeletion.AccountDeletionRepository.ExternalIdentity;
import io.contractforge.backend.application.accountdeletion.AccountDeletionTombstone;
import io.contractforge.backend.application.accountdeletion.AccountDeletionTombstonePort;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Ephemeral local-only tombstones; production uses the environment-separated durable AWS ledger. */
@Component
@Profile("!aws | local")
final class LocalAccountDeletionTombstoneAdapter implements AccountDeletionTombstonePort {
    private final byte[] localKey = new byte[32];
    private final ConcurrentHashMap<String, UUID> subjectIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AccountDeletionTombstone> tombstones = new ConcurrentHashMap<>();

    LocalAccountDeletionTombstoneAdapter() {
        new SecureRandom().nextBytes(localKey);
    }

    @Override
    public String fingerprint(String provider, String providerSubject) {
        if (provider == null || provider.isBlank() || providerSubject == null || providerSubject.isBlank()) {
            throw new IllegalArgumentException("External identity is incomplete");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(localKey, "HmacSHA256"));
            mac.update(provider.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return "local_" + HexFormat.of().formatHex(mac.doFinal(providerSubject.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Local account-deletion fingerprint is unavailable", failure);
        }
    }

    @Override
    public synchronized void recordInProgress(UUID deletionId, UUID userId, List<ExternalIdentity> identities,
                                              OffsetDateTime fencedAt) {
        List<String> fingerprints = identities.stream().map(identity -> fingerprint(identity.provider(),
                identity.providerSubject())).sorted().toList();
        AccountDeletionTombstone existing = tombstones.get(deletionId);
        if (existing != null) {
            if (!existing.userId().equals(userId) || !existing.identityFingerprints().equals(fingerprints)) {
                throw new IllegalStateException("Local account-deletion tombstone conflicts with the active deletion");
            }
            return;
        }
        for (String fingerprint : fingerprints) {
            UUID other = subjectIndex.get(fingerprint);
            if (other != null && !other.equals(deletionId)) {
                throw new IllegalStateException("Provider identity is already fenced by another deletion");
            }
        }
        fingerprints.forEach(fingerprint -> subjectIndex.put(fingerprint, deletionId));
        tombstones.put(deletionId, new AccountDeletionTombstone(deletionId, userId, "local-source", fingerprints,
                AccountDeletionTombstone.State.IN_PROGRESS, fencedAt, null, null, null, 0));
    }

    @Override
    public boolean blocksProvisioning(String provider, String providerSubject) {
        return subjectIndex.containsKey(fingerprint(provider, providerSubject));
    }

    @Override
    public Optional<AccountDeletionTombstone> find(UUID deletionId) {
        return Optional.ofNullable(tombstones.get(deletionId));
    }

    @Override
    public synchronized void complete(UUID deletionId, UUID userId, OffsetDateTime completedAt) {
        AccountDeletionTombstone current = require(deletionId);
        if (!current.userId().equals(userId)) throw new IllegalStateException("Deletion tombstone owner changed");
        tombstones.put(deletionId, new AccountDeletionTombstone(deletionId, userId, current.sourceDbResourceId(),
                current.identityFingerprints(), AccountDeletionTombstone.State.COMPLETED,
                current.fencedAt(), current.completedAt() == null ? completedAt : current.completedAt(),
                current.lastRecoveryCheckAt(), current.oldestRecoverablePointAt(), current.recoverablePointCount()));
    }

    @Override
    public List<AccountDeletionTombstone> findAll() {
        return List.copyOf(tombstones.values());
    }

    @Override
    public synchronized void recordRecoveryCheck(UUID deletionId, OffsetDateTime checkedAt,
                                                Optional<OffsetDateTime> oldestRecoverablePointAt,
                                                int recoverablePointCount) {
        AccountDeletionTombstone current = require(deletionId);
        if (current.state() != AccountDeletionTombstone.State.COMPLETED
                || (recoverablePointCount == 0 && oldestRecoverablePointAt.isPresent())) {
            throw new IllegalStateException("Recovery-point assessment is incomplete");
        }
        tombstones.put(deletionId, new AccountDeletionTombstone(deletionId, current.userId(), current.sourceDbResourceId(),
                current.identityFingerprints(), current.state(), current.fencedAt(), current.completedAt(), checkedAt,
                oldestRecoverablePointAt.orElse(null), recoverablePointCount));
    }

    @Override
    public synchronized void releaseIfNoRecoverablePoints(UUID deletionId) {
        AccountDeletionTombstone current = require(deletionId);
        if (current.state() != AccountDeletionTombstone.State.COMPLETED || current.lastRecoveryCheckAt() == null
                || current.recoverablePointCount() != 0) {
            throw new IllegalStateException("Account deletion tombstone still has a recovery hold");
        }
        current.identityFingerprints().forEach(subjectIndex::remove);
        tombstones.remove(deletionId);
    }

    @Override
    public synchronized void discardInProgress(UUID deletionId) {
        AccountDeletionTombstone current = tombstones.get(deletionId);
        if (current == null || current.state() != AccountDeletionTombstone.State.IN_PROGRESS) return;
        current.identityFingerprints().forEach(subjectIndex::remove);
        tombstones.remove(deletionId);
    }

    private AccountDeletionTombstone require(UUID deletionId) {
        AccountDeletionTombstone tombstone = tombstones.get(deletionId);
        if (tombstone == null) throw new IllegalStateException("Account deletion tombstone does not exist");
        return tombstone;
    }
}
