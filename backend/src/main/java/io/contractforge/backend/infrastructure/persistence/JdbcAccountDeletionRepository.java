package io.contractforge.backend.infrastructure.persistence;

import io.contractforge.backend.application.accountdeletion.AccountDeletionRepository;
import io.contractforge.backend.domain.model.AccountDeletionJob;
import io.contractforge.backend.domain.model.AccountDeletionObjectKind;
import io.contractforge.backend.domain.model.AccountDeletionPhase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!test")
class JdbcAccountDeletionRepository implements AccountDeletionRepository {
    private static final RowMapper<AccountDeletionJob> JOB_MAPPER = JdbcAccountDeletionRepository::mapJob;
    private final JdbcTemplate jdbc;

    JdbcAccountDeletionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AccountDeletionRepository.BeginResult> begin(UUID userId, UUID jobId, OffsetDateTime now,
                                                                 OffsetDateTime identityFenceUntil,
                                                                 Duration uploadWriteGrace) {
        List<String> statuses = jdbc.query("SELECT status FROM users WHERE id = ? FOR UPDATE",
                (row, index) -> row.getString("status"), userId);
        if (statuses.isEmpty()) return Optional.empty();
        if (!"ACTIVE".equals(statuses.getFirst())) {
            return findForUser(userId).map(id -> new AccountDeletionRepository.BeginResult(id, false));
        }

        OffsetDateTime latestCapability = jdbc.queryForObject(
                "SELECT MAX(expires_at) FROM uploads WHERE user_id = ?",
                (row, index) -> row.getObject(1, OffsetDateTime.class), userId);
        OffsetDateTime capabilitiesExpireAt = latestCapability == null ? now
                : latestCapability.isAfter(now) ? latestCapability.plus(uploadWriteGrace) : now.plus(uploadWriteGrace);

        jdbc.update("UPDATE users SET status = 'DISABLED', updated_at = ? WHERE id = ? AND status = 'ACTIVE'",
                now, userId);
        jdbc.update("UPDATE user_identities SET email = NULL WHERE user_id = ?", userId);
        jdbc.update("INSERT INTO account_deletion_jobs (id, user_id, phase, upload_capabilities_expire_at, "
                        + "identity_fence_until, available_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", jobId, userId, AccountDeletionPhase.DISABLE_COGNITO.name(),
                capabilitiesExpireAt, identityFenceUntil, now, now, now);
        return Optional.of(new AccountDeletionRepository.BeginResult(jobId, true));
    }

    @Override
    public Optional<UUID> findForUser(UUID userId) {
        return jdbc.query("SELECT id FROM account_deletion_jobs WHERE user_id = ?",
                (row, index) -> row.getObject("id", UUID.class), userId).stream().findFirst();
    }

    @Override
    public Optional<AccountDeletionJob> find(UUID jobId) {
        return jdbc.query("SELECT id, user_id, phase, upload_capabilities_expire_at, identity_fence_until, "
                        + "available_at, worker_token FROM account_deletion_jobs WHERE id = ?", JOB_MAPPER, jobId)
                .stream().findFirst();
    }

    @Override
    public List<AccountDeletionRepository.ExternalIdentity> findIdentities(UUID userId) {
        return jdbc.query("SELECT provider, provider_subject FROM user_identities WHERE user_id = ? "
                        + "ORDER BY provider, provider_subject",
                (row, index) -> new AccountDeletionRepository.ExternalIdentity(
                        row.getString("provider"), row.getString("provider_subject")), userId);
    }

    @Override
    public List<UUID> findReadyToDispatch(OffsetDateTime now, int limit) {
        return jdbc.query("SELECT id FROM account_deletion_jobs WHERE available_at <= ? AND enqueued_at IS NULL "
                        + "AND (dispatch_lease_until IS NULL OR dispatch_lease_until <= ?) "
                        + "ORDER BY available_at, created_at LIMIT ?",
                (row, index) -> row.getObject("id", UUID.class), now, now, limit);
    }

    @Override
    public boolean claimDispatch(UUID jobId, UUID dispatchToken, OffsetDateTime now, OffsetDateTime leaseUntil) {
        return jdbc.update("UPDATE account_deletion_jobs SET dispatch_token = ?, dispatch_lease_until = ? "
                        + "WHERE id = ? AND available_at <= ? AND enqueued_at IS NULL "
                        + "AND (dispatch_lease_until IS NULL OR dispatch_lease_until <= ?)",
                dispatchToken, leaseUntil, jobId, now, now) == 1;
    }

    @Override
    public boolean markEnqueued(UUID jobId, UUID dispatchToken, OffsetDateTime now) {
        return jdbc.update("UPDATE account_deletion_jobs SET enqueued_at = ?, dispatch_token = NULL, "
                        + "dispatch_lease_until = NULL, updated_at = ? WHERE id = ? AND dispatch_token = ?",
                now, now, jobId, dispatchToken) == 1;
    }

    @Override
    public void releaseDispatch(UUID jobId, UUID dispatchToken) {
        jdbc.update("UPDATE account_deletion_jobs SET dispatch_token = NULL, dispatch_lease_until = NULL "
                        + "WHERE id = ? AND dispatch_token = ?", jobId, dispatchToken);
    }

    @Override
    public Optional<AccountDeletionJob> claim(UUID jobId, UUID workerToken, OffsetDateTime now,
                                              OffsetDateTime leaseUntil) {
        int claimed = jdbc.update("UPDATE account_deletion_jobs SET worker_token = ?, worker_lease_until = ? "
                        + "WHERE id = ? AND available_at <= ? "
                        + "AND (worker_lease_until IS NULL OR worker_lease_until <= ?)",
                workerToken, leaseUntil, jobId, now, now);
        return claimed == 1 ? find(jobId) : Optional.empty();
    }

    @Override
    public Optional<AccountDeletionJob> claimInitialDisable(UUID jobId, UUID workerToken, OffsetDateTime now,
                                                            OffsetDateTime leaseUntil) {
        int claimed = jdbc.update("UPDATE account_deletion_jobs SET worker_token = ?, worker_lease_until = ? "
                        + "WHERE id = ? AND phase = ? AND available_at <= ? "
                        + "AND (worker_lease_until IS NULL OR worker_lease_until <= ?)",
                workerToken, leaseUntil, jobId, AccountDeletionPhase.DISABLE_COGNITO.name(), now, now);
        return claimed == 1 ? find(jobId) : Optional.empty();
    }

    @Override
    public void reschedule(AccountDeletionJob job, OffsetDateTime availableAt, OffsetDateTime now) {
        int updated = jdbc.update("UPDATE account_deletion_jobs SET available_at = ?, enqueued_at = NULL, "
                        + "dispatch_token = NULL, dispatch_lease_until = NULL, worker_token = NULL, "
                        + "worker_lease_until = NULL, updated_at = ? WHERE id = ? AND worker_token = ?",
                availableAt, now, job.id(), job.workerToken());
        requireOwnedJobUpdate(updated);
    }

    @Override
    public void advance(AccountDeletionJob job, AccountDeletionPhase nextPhase,
                        OffsetDateTime availableAt, OffsetDateTime now) {
        int updated = jdbc.update("UPDATE account_deletion_jobs SET phase = ?, available_at = ?, enqueued_at = NULL, "
                        + "dispatch_token = NULL, dispatch_lease_until = NULL, worker_token = NULL, "
                        + "worker_lease_until = NULL, updated_at = ? WHERE id = ? AND phase = ? AND worker_token = ?",
                nextPhase.name(), availableAt, now, job.id(), job.phase().name(), job.workerToken());
        requireOwnedJobUpdate(updated);
    }

    @Override
    public List<OwnedUpload> findOwnedUploads(UUID userId) {
        return jdbc.query("SELECT id, object_key FROM uploads WHERE user_id = ? ORDER BY id",
                (row, index) -> new OwnedUpload(row.getObject("id", UUID.class), row.getString("object_key")), userId);
    }

    @Override
    public List<String> findOwnedTrustedObjectKeys(UUID userId) {
        return jdbc.query("SELECT storage_key FROM documents WHERE user_id = ? ORDER BY id",
                (row, index) -> row.getString("storage_key"), userId);
    }

    @Override
    public void captureObjects(UUID jobId, List<PendingObject> objects) {
        if (objects.isEmpty()) return;
        jdbc.batchUpdate("INSERT INTO account_deletion_objects (deletion_id, object_key, object_kind) "
                        + "VALUES (?, ?, ?) ON CONFLICT (deletion_id, object_key) DO NOTHING", objects,
                objects.size(), (statement, object) -> {
                    statement.setObject(1, jobId);
                    statement.setString(2, object.objectKey());
                    statement.setString(3, object.kind().name());
                });
    }

    @Override
    public Optional<PendingObject> findNextPendingObject(UUID jobId) {
        return jdbc.query("SELECT object_key, object_kind FROM account_deletion_objects "
                        + "WHERE deletion_id = ? AND deleted_at IS NULL ORDER BY object_key LIMIT 1",
                (row, index) -> new PendingObject(row.getString("object_key"),
                        AccountDeletionObjectKind.valueOf(row.getString("object_kind"))), jobId)
                .stream().findFirst();
    }

    @Override
    public void markObjectDeleted(AccountDeletionJob job, String objectKey, OffsetDateTime now) {
        int updated = jdbc.update("WITH marked AS (UPDATE account_deletion_objects o SET deleted_at = ? "
                        + "WHERE o.deletion_id = ? AND o.object_key = ? AND o.deleted_at IS NULL "
                        + "AND EXISTS (SELECT 1 FROM account_deletion_jobs j WHERE j.id = o.deletion_id "
                        + "AND j.worker_token = ?) RETURNING o.deletion_id) "
                        + "UPDATE account_deletion_jobs j SET enqueued_at = NULL, worker_token = NULL, "
                        + "worker_lease_until = NULL, updated_at = ? FROM marked m "
                        + "WHERE j.id = m.deletion_id AND j.worker_token = ?",
                now, job.id(), objectKey, job.workerToken(), now, job.workerToken());
        if (updated > 1) throw new IllegalStateException("Account deletion object update was not unique");
    }

    @Override
    public void purgeRelationalData(AccountDeletionJob job) {
        UUID userId = job.userId();
        jdbc.update("DELETE FROM audit_events WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM usage_records WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM events WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM recommendations WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM documents WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM uploads WHERE user_id = ?", userId);
    }

    @Override
    public void complete(AccountDeletionJob job, UUID systemUserId, OffsetDateTime now, String auditAction) {
        jdbc.update("INSERT INTO audit_events (id, user_id, resource_type, resource_id, action, created_at) "
                        + "VALUES (?, ?, 'ACCOUNT_DELETION', ?, ?, ?)", UUID.randomUUID(), systemUserId,
                job.id(), auditAction, now);
        int deletedJob = jdbc.update("DELETE FROM account_deletion_jobs WHERE id = ? AND phase = ? AND worker_token = ?",
                job.id(), AccountDeletionPhase.WAIT_IDENTITY_FENCE.name(), job.workerToken());
        requireOwnedJobUpdate(deletedJob);
        jdbc.update("DELETE FROM user_identities WHERE user_id = ?", job.userId());
        int deletedUser = jdbc.update("DELETE FROM users WHERE id = ? AND status = 'DISABLED'", job.userId());
        if (deletedUser != 1) throw new IllegalStateException("Account deletion user removal did not complete");
    }

    private static AccountDeletionJob mapJob(ResultSet row, int rowNumber) throws SQLException {
        return new AccountDeletionJob(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                AccountDeletionPhase.valueOf(row.getString("phase")),
                row.getObject("upload_capabilities_expire_at", OffsetDateTime.class),
                row.getObject("identity_fence_until", OffsetDateTime.class),
                row.getObject("available_at", OffsetDateTime.class), row.getObject("worker_token", UUID.class));
    }

    private void requireOwnedJobUpdate(int updated) {
        if (updated != 1) throw new IllegalStateException("Account deletion lease or phase changed");
    }
}
