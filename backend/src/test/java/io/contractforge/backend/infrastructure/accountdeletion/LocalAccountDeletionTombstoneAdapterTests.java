package io.contractforge.backend.infrastructure.accountdeletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.contractforge.backend.application.accountdeletion.AccountDeletionRepository.ExternalIdentity;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalAccountDeletionTombstoneAdapterTests {
    @Test
    void blocksTheDeletedIdentityUntilAnExplicitRecoveryBasedRelease() {
        var adapter = new LocalAccountDeletionTombstoneAdapter();
        UUID deletionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String provider = "cognito";
        String subject = "private-provider-subject";
        OffsetDateTime fencedAt = OffsetDateTime.parse("2026-09-01T12:00:00Z");
        adapter.recordInProgress(deletionId, userId, List.of(new ExternalIdentity(provider, subject)), fencedAt);

        assertThat(adapter.blocksProvisioning(provider, subject)).isTrue();
        assertThat(adapter.findAll().getFirst().toString()).doesNotContain(subject);
        adapter.complete(deletionId, userId, fencedAt.plusMinutes(2));
        assertThatThrownBy(() -> adapter.releaseIfNoRecoverablePoints(deletionId))
                .isInstanceOf(IllegalStateException.class);

        adapter.recordRecoveryCheck(deletionId, fencedAt.plusDays(20), Optional.empty(), 0);
        adapter.releaseIfNoRecoverablePoints(deletionId);
        assertThat(adapter.blocksProvisioning(provider, subject)).isFalse();
        assertThat(adapter.findAll()).isEmpty();
    }

    @Test
    void recoveryPointHoldPreventsLocalTombstoneRelease() {
        var adapter = new LocalAccountDeletionTombstoneAdapter();
        UUID deletionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-01T12:00:00Z");
        adapter.recordInProgress(deletionId, userId,
                List.of(new ExternalIdentity("cognito", "held-subject")), now);
        adapter.complete(deletionId, userId, now.plusMinutes(1));
        adapter.recordRecoveryCheck(deletionId, now.plusDays(15), Optional.of(now), 1);

        assertThatThrownBy(() -> adapter.releaseIfNoRecoverablePoints(deletionId))
                .isInstanceOf(IllegalStateException.class);
        assertThat(adapter.blocksProvisioning("cognito", "held-subject")).isTrue();
    }
}
