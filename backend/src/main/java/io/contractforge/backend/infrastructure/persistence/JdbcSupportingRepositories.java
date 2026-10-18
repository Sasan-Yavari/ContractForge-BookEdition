package io.contractforge.backend.infrastructure.persistence;

import io.contractforge.backend.application.port.RepositoryPorts;
import io.contractforge.backend.application.port.ActiveAccountWritePort;
import io.contractforge.backend.domain.model.NotificationChannel;
import io.contractforge.backend.domain.model.NotificationDeliveryStatus;
import io.contractforge.backend.application.identity.ProviderSubjectAlreadyExistsException;
import io.contractforge.backend.domain.model.PersistenceModels;
import io.contractforge.backend.domain.model.UploadStatus;
import io.contractforge.backend.domain.model.ProcessingAttemptStatus;
import io.contractforge.backend.domain.model.ContractType;
import io.contractforge.backend.domain.model.EventStatus;
import io.contractforge.backend.domain.model.MonitoringEventType;
import io.contractforge.backend.domain.model.RecommendationType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

@Repository("userRepository")
@Profile("!test")
class JdbcUserRepository implements RepositoryPorts.UserRepository, ActiveAccountWritePort {
    private final JdbcTemplate jdbc;

    JdbcUserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<PersistenceModels.User> findById(UUID id) {
        List<PersistenceModels.User> rows = jdbc.query("SELECT id, status, created_at, updated_at FROM users WHERE id = ?",
                (r, n) -> new PersistenceModels.User(r.getObject("id", UUID.class), r.getString("status"),
                        r.getObject("created_at", java.time.OffsetDateTime.class), r.getObject("updated_at", java.time.OffsetDateTime.class)), id);
        return rows.stream().findFirst();
    }

    @Override
    public boolean lockActiveUser(UUID id) {
        return jdbc.query("SELECT status FROM users WHERE id = ? FOR SHARE", (r, n) -> r.getString("status"), id)
                .stream().anyMatch("ACTIVE"::equals);
    }

    public void save(PersistenceModels.User user) {
        jdbc.update("INSERT INTO users (id, status, created_at, updated_at) VALUES (?, ?, ?, ?)",
                user.id(), user.status().value(), user.createdAt(), user.updatedAt());
    }
}

@Repository("userIdentityRepository")
@Profile("!test")
class JdbcUserIdentityRepository implements RepositoryPorts.UserIdentityRepository {
    private final JdbcTemplate jdbc;

    JdbcUserIdentityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<PersistenceModels.UserIdentity> findByProviderSubject(String provider, String providerSubject) {
        List<PersistenceModels.UserIdentity> rows = jdbc.query("SELECT id, user_id, provider, provider_subject, email, created_at "
                + "FROM user_identities WHERE provider = ? AND provider_subject = ?", (r, n) ->
                new PersistenceModels.UserIdentity(r.getObject("id", UUID.class), r.getObject("user_id", UUID.class),
                        r.getString("provider"), r.getString("provider_subject"), r.getString("email"),
                        r.getObject("created_at", java.time.OffsetDateTime.class)),
                provider, providerSubject);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<String> findTrustedEmailByUserId(UUID userId) {
        List<String> rows = jdbc.query("SELECT email FROM user_identities WHERE user_id = ? AND email IS NOT NULL "
                        + "ORDER BY created_at ASC LIMIT 1", (r, n) -> r.getString("email"), userId);
        return rows.stream().findFirst();
    }

    @Override
    public void updateTrustedEmail(UUID identityId, String email) {
        jdbc.update("UPDATE user_identities SET email = ? WHERE id = ?", email, identityId);
    }

    public void save(PersistenceModels.UserIdentity identity) {
        try {
            jdbc.update("INSERT INTO user_identities (id, user_id, provider, provider_subject, email, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", identity.id(), identity.userId(), identity.provider(),
                    identity.providerSubject(), identity.email(), identity.createdAt());
        } catch (DataIntegrityViolationException exception) {
            if (isProviderSubjectUniqueViolation(exception)) {
                throw new ProviderSubjectAlreadyExistsException(exception);
            }
            throw exception;
        }
    }

    private boolean isProviderSubjectUniqueViolation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof PSQLException postgresException
                    && PSQLState.UNIQUE_VIOLATION.getState().equals(postgresException.getSQLState())
                    && postgresException.getServerErrorMessage() != null
                    && "user_identities_provider_subject_unique".equals(
                            postgresException.getServerErrorMessage().getConstraint())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

@Repository("eventRepository")
@Profile("!test")
class JdbcEventRepository implements RepositoryPorts.EventRepository {
    private final JdbcTemplate jdbc;

    JdbcEventRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void save(PersistenceModels.Event event) {
        jdbc.update("INSERT INTO events (id, user_id, document_id, type, description, event_date, amount, currency, status, source_reference, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", event.id(), event.userId(), event.documentId(), event.type().value(),
                event.description(), event.eventDate(), event.amount(), event.currency(), event.status().value(), event.sourceReference(), event.createdAt());
    }

    @Override
    public int saveIfAbsent(PersistenceModels.Event event) {
        return jdbc.update("INSERT INTO events (id, user_id, document_id, type, description, event_date, amount, currency, status, source_reference, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (document_id, type, source_reference) WHERE source_reference IS NOT NULL DO UPDATE "
                        + "SET status = EXCLUDED.status, description = EXCLUDED.description, event_date = EXCLUDED.event_date "
                        + "WHERE events.status = ?",
                event.id(), event.userId(), event.documentId(), event.type().value(), event.description(), event.eventDate(),
                event.amount(), event.currency(), event.status().value(), event.sourceReference(), event.createdAt(),
                EventStatus.CANCELLED.value());
    }

    @Override
    public int cancelCurrentContractEvents(UUID documentId) {
        return jdbc.update("UPDATE events SET status = ? WHERE document_id = ? AND status = ? "
                        + "AND source_reference LIKE ? AND type IN (?, ?)",
                EventStatus.CANCELLED.value(), documentId, EventStatus.ACTIVE.value(), "contract-term:%",
                MonitoringEventType.CONTRACT_EXPIRING.value(), MonitoringEventType.CONTRACT_RENEWING.value());
    }

    @Override
    public List<PersistenceModels.Event> findByUserId(UUID userId) {
        return jdbc.query("SELECT id, user_id, document_id, type, description, event_date, amount, currency, status, "
                        + "source_reference, created_at FROM events WHERE user_id = ? ORDER BY event_date DESC, id DESC",
                this::mapEvent, userId);
    }

    @Override
    public List<PersistenceModels.Event> findByUserIdAndDocumentId(UUID userId, UUID documentId) {
        return jdbc.query("SELECT id, user_id, document_id, type, description, event_date, amount, currency, status, "
                        + "source_reference, created_at FROM events WHERE user_id = ? AND document_id = ? "
                        + "ORDER BY event_date DESC, id DESC",
                this::mapEvent, userId, documentId);
    }

    public Optional<PersistenceModels.Event> findById(UUID id) {
        List<PersistenceModels.Event> rows = jdbc.query("SELECT id, user_id, document_id, type, description, event_date, amount, currency, status, "
                + "source_reference, created_at FROM events WHERE id = ?", this::mapEvent, id);
        return rows.stream().findFirst();
    }

    private PersistenceModels.Event mapEvent(java.sql.ResultSet rs, int rowNumber)
            throws java.sql.SQLException {
        return new PersistenceModels.Event(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getString("type"), rs.getString("description"),
                rs.getObject("event_date", java.time.OffsetDateTime.class), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getString("status"), rs.getString("source_reference"),
                rs.getObject("created_at", java.time.OffsetDateTime.class));
    }
}

@Repository("recommendationRepository")
@Profile("!test")
class JdbcRecommendationRepository implements RepositoryPorts.RecommendationRepository {
    private final JdbcTemplate jdbc;

    JdbcRecommendationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void save(PersistenceModels.Recommendation recommendation) {
        jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO recommendations "
                        + "(id, user_id, document_id, type, data, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)");
                statement.setObject(1, recommendation.id());
                statement.setObject(2, recommendation.userId());
                statement.setObject(3, recommendation.documentId());
                statement.setString(4, recommendation.type().value());
                statement.setObject(5, JdbcJsonb.value(recommendation.data()));
                statement.setObject(6, recommendation.createdAt());
                statement.setObject(7, recommendation.expiresAt());
                return statement;
            });
    }

    @Override
    public int saveIfAbsent(PersistenceModels.Recommendation recommendation) {
        return jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO recommendations "
                    + "(id, user_id, document_id, type, data, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, expires_at = EXCLUDED.expires_at "
                        + "WHERE recommendations.expires_at IS NOT NULL");
            statement.setObject(1, recommendation.id());
            statement.setObject(2, recommendation.userId());
            statement.setObject(3, recommendation.documentId());
                statement.setString(4, recommendation.type().value());
            statement.setObject(5, JdbcJsonb.value(recommendation.data()));
            statement.setObject(6, recommendation.createdAt());
            statement.setObject(7, recommendation.expiresAt());
            return statement;
        });
    }

    @Override
    public int expireCurrentContractRecommendations(UUID documentId, java.time.OffsetDateTime expiredAt) {
        return jdbc.update("UPDATE recommendations SET expires_at = ? "
                        + "WHERE document_id = ? AND expires_at IS NULL AND type IN (?, ?) "
                        + "AND data #>> '{evidence,source_reference}' LIKE ?",
                expiredAt, documentId, RecommendationType.CONTRACT_EXPIRY.value(), RecommendationType.RENEWAL.value(),
                "contract-term:%");
    }

    @Override
    public List<PersistenceModels.Recommendation> findByUserId(UUID userId, boolean includeHistory,
                                                               java.time.OffsetDateTime evaluatedAt) {
        String historyPredicate = includeHistory ? "" : " AND (expires_at IS NULL OR expires_at > ?)";
        String sql = "SELECT id, user_id, document_id, type, data, created_at, expires_at FROM recommendations "
                + "WHERE user_id = ?" + historyPredicate + " ORDER BY created_at DESC, id DESC";
        return includeHistory
                ? jdbc.query(sql, this::mapRecommendation, userId)
                : jdbc.query(sql, this::mapRecommendation, userId, evaluatedAt);
    }

    @Override
    public List<PersistenceModels.Recommendation> findByUserIdAndDocumentId(UUID userId, UUID documentId,
                                                                             boolean includeHistory,
                                                                             java.time.OffsetDateTime evaluatedAt) {
        String historyPredicate = includeHistory ? "" : " AND (expires_at IS NULL OR expires_at > ?)";
        String sql = "SELECT id, user_id, document_id, type, data, created_at, expires_at FROM recommendations "
                + "WHERE user_id = ? AND document_id = ?" + historyPredicate + " ORDER BY created_at DESC, id DESC";
        return includeHistory
                ? jdbc.query(sql, this::mapRecommendation, userId, documentId)
                : jdbc.query(sql, this::mapRecommendation, userId, documentId, evaluatedAt);
    }

    public Optional<PersistenceModels.Recommendation> findById(UUID id) {
        List<PersistenceModels.Recommendation> rows = jdbc.query("SELECT id, user_id, document_id, type, data, created_at, expires_at "
                + "FROM recommendations WHERE id = ?", this::mapRecommendation, id);
        return rows.stream().findFirst();
    }

    private PersistenceModels.Recommendation mapRecommendation(java.sql.ResultSet rs, int rowNumber)
            throws java.sql.SQLException {
        return new PersistenceModels.Recommendation(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getString("type"), JdbcJsonb.read(rs.getObject("data")),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("expires_at", java.time.OffsetDateTime.class));
    }
}

@Repository("notificationRepository")
@Profile("!test")
class JdbcNotificationRepository implements RepositoryPorts.NotificationRepository {
    private final JdbcTemplate jdbc;

    JdbcNotificationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public int saveIfAbsent(PersistenceModels.Notification notification) {
        return jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO notifications "
                    + "(id, recommendation_id, notification_type, data, created_at) VALUES (?, ?, ?, ?, ?) "
                    + "ON CONFLICT (recommendation_id) DO NOTHING");
            statement.setObject(1, notification.id());
            statement.setObject(2, notification.recommendationId());
            statement.setString(3, notification.notificationType().name());
            statement.setObject(4, JdbcJsonb.value(notification.data()));
            statement.setObject(5, notification.createdAt());
            return statement;
        });
    }

    @Override
    public Optional<PersistenceModels.Notification> findByRecommendationId(UUID recommendationId) {
        List<PersistenceModels.Notification> rows = jdbc.query("SELECT id, recommendation_id, notification_type, data, created_at "
                + "FROM notifications WHERE recommendation_id = ?", (r, n) ->
                new PersistenceModels.Notification(r.getObject("id", UUID.class),
                        r.getObject("recommendation_id", UUID.class), r.getString("notification_type"),
                        JdbcJsonb.read(r.getObject("data")), r.getObject("created_at", java.time.OffsetDateTime.class)),
                recommendationId);
        return rows.stream().findFirst();
    }

}

@Repository("notificationDeliveryRepository")
@Profile("!test")
class JdbcNotificationDeliveryRepository implements RepositoryPorts.NotificationDeliveryRepository {
    private final JdbcTemplate jdbc;

    JdbcNotificationDeliveryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public int saveIfAbsent(PersistenceModels.NotificationDelivery delivery) {
        return jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO notification_deliveries "
                    + "(id, notification_id, channel, status, destination, attempt_count, in_flight_at, lease_until, "
                    + "claim_token, last_attempt_at, delivered_at, last_failure_at, failure_code, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (notification_id, channel) DO NOTHING");
            statement.setObject(1, delivery.id());
            statement.setObject(2, delivery.notificationId());
            statement.setString(3, delivery.channel().name());
            statement.setString(4, delivery.status().name());
            statement.setString(5, delivery.destination());
            statement.setInt(6, delivery.attemptCount());
            statement.setObject(7, delivery.inFlightAt());
            statement.setObject(8, delivery.leaseUntil());
            statement.setObject(9, delivery.claimToken());
            statement.setObject(10, delivery.lastAttemptAt());
            statement.setObject(11, delivery.deliveredAt());
            statement.setObject(12, delivery.lastFailureAt());
            statement.setString(13, delivery.failureCode());
            statement.setObject(14, delivery.createdAt());
            statement.setObject(15, delivery.updatedAt());
            return statement;
        });
    }

    @Override
    public Optional<PersistenceModels.NotificationDelivery> findById(UUID id) {
        return jdbc.query("SELECT id, notification_id, channel, status, destination, attempt_count, in_flight_at, "
                        + "lease_until, claim_token, last_attempt_at, delivered_at, last_failure_at, failure_code, "
                        + "created_at, updated_at FROM notification_deliveries WHERE id = ?", this::map, id)
                .stream().findFirst();
    }

    @Override
    public List<PersistenceModels.NotificationDelivery> findQueued() {
        return jdbc.query("SELECT d.id, d.notification_id, d.channel, d.status, d.destination, d.attempt_count, d.in_flight_at, "
                + "d.lease_until, d.claim_token, d.last_attempt_at, d.delivered_at, d.last_failure_at, d.failure_code, "
                + "d.created_at, d.updated_at FROM notification_deliveries d JOIN notifications n ON n.id = d.notification_id "
                + "JOIN recommendations r ON r.id = n.recommendation_id JOIN users u ON u.id = r.user_id "
                + "WHERE d.status = ? AND d.channel = ? AND d.destination IS NOT NULL AND u.status = 'ACTIVE' "
                + "ORDER BY d.created_at, d.id", this::map,
                NotificationDeliveryStatus.QUEUED.name(), NotificationChannel.EMAIL.name());
    }

    @Override
    public Optional<PersistenceModels.NotificationDeliveryWork> claim(UUID deliveryId, UUID notificationId,
                                                                       java.time.OffsetDateTime claimedAt,
                                                                       java.time.OffsetDateTime leaseUntil,
                                                                       UUID claimToken) {
        int updated = jdbc.update("UPDATE notification_deliveries SET status = ?, attempt_count = attempt_count + 1, "
                        + "in_flight_at = ?, lease_until = ?, claim_token = ?, last_attempt_at = ?, updated_at = ? "
                        + "WHERE id = ? AND notification_id = ? AND channel = ? AND status = ? AND destination IS NOT NULL "
                        + "AND EXISTS (SELECT 1 FROM notifications n JOIN recommendations r ON r.id = n.recommendation_id "
                        + "JOIN users u ON u.id = r.user_id WHERE n.id = notification_deliveries.notification_id "
                        + "AND u.status = 'ACTIVE')",
                NotificationDeliveryStatus.IN_FLIGHT.name(), claimedAt, leaseUntil, claimToken, claimedAt, claimedAt,
                deliveryId, notificationId, NotificationChannel.EMAIL.name(), NotificationDeliveryStatus.QUEUED.name());
        if (updated == 0) {
            return Optional.empty();
        }
        return jdbc.query("SELECT d.id, d.notification_id, d.channel, d.status, d.destination, d.attempt_count, "
                        + "d.in_flight_at, d.lease_until, d.claim_token, d.last_attempt_at, d.delivered_at, "
                        + "d.last_failure_at, d.failure_code, d.created_at, d.updated_at, n.id AS n_id, "
                        + "n.recommendation_id, n.notification_type, n.data, n.created_at AS n_created_at "
                        + "FROM notification_deliveries d JOIN notifications n ON n.id = d.notification_id "
                        + "JOIN recommendations rec ON rec.id = n.recommendation_id "
                        + "JOIN users u ON u.id = rec.user_id AND u.status = 'ACTIVE' "
                        + "WHERE d.id = ? AND d.claim_token = ?", (r, n) -> {
                    PersistenceModels.NotificationDelivery delivery = map(r, n);
                    PersistenceModels.Notification notification = new PersistenceModels.Notification(
                            r.getObject("n_id", UUID.class), r.getObject("recommendation_id", UUID.class),
                            r.getString("notification_type"), JdbcJsonb.read(r.getObject("data")),
                            r.getObject("n_created_at", java.time.OffsetDateTime.class));
                    return new PersistenceModels.NotificationDeliveryWork(delivery, notification);
                }, deliveryId, claimToken).stream().findFirst();
    }

    @Override
    public Optional<PersistenceModels.NotificationDeliveryWork> findClaimed(UUID deliveryId, UUID claimToken,
                                                                              java.time.OffsetDateTime now) {
        return jdbc.query("SELECT d.id, d.notification_id, d.channel, d.status, d.destination, d.attempt_count, "
                        + "d.in_flight_at, d.lease_until, d.claim_token, d.last_attempt_at, d.delivered_at, "
                        + "d.last_failure_at, d.failure_code, d.created_at, d.updated_at, n.id AS n_id, "
                        + "n.recommendation_id, n.notification_type, n.data, n.created_at AS n_created_at "
                        + "FROM notification_deliveries d JOIN notifications n ON n.id = d.notification_id "
                        + "JOIN recommendations rec ON rec.id = n.recommendation_id "
                        + "JOIN users u ON u.id = rec.user_id AND u.status = 'ACTIVE' "
                        + "WHERE d.id = ? AND d.claim_token = ? AND d.status = ? AND d.channel = ? "
                        + "AND d.destination IS NOT NULL AND d.lease_until > ?", (r, n) -> {
                    PersistenceModels.NotificationDelivery delivery = map(r, n);
                    PersistenceModels.Notification notification = new PersistenceModels.Notification(
                            r.getObject("n_id", UUID.class), r.getObject("recommendation_id", UUID.class),
                            r.getString("notification_type"), JdbcJsonb.read(r.getObject("data")),
                            r.getObject("n_created_at", java.time.OffsetDateTime.class));
                    return new PersistenceModels.NotificationDeliveryWork(delivery, notification);
                }, deliveryId, claimToken, NotificationDeliveryStatus.IN_FLIGHT.name(),
                NotificationChannel.EMAIL.name(), now).stream().findFirst();
    }

    @Override
    public int recoverExpiredClaims(java.time.OffsetDateTime now) {
        return jdbc.update("UPDATE notification_deliveries SET status = ?, in_flight_at = NULL, lease_until = NULL, "
                        + "claim_token = NULL, updated_at = ? WHERE status = ? AND lease_until <= ?",
                NotificationDeliveryStatus.QUEUED.name(), now, NotificationDeliveryStatus.IN_FLIGHT.name(), now);
    }

    @Override
    public int markDelivered(UUID deliveryId, UUID claimToken, java.time.OffsetDateTime deliveredAt) {
        return jdbc.update("UPDATE notification_deliveries SET status = ?, delivered_at = ?, in_flight_at = NULL, "
                        + "lease_until = NULL, claim_token = NULL, updated_at = ? WHERE id = ? AND claim_token = ? "
                        + "AND status = ?", NotificationDeliveryStatus.DELIVERED.name(), deliveredAt, deliveredAt,
                deliveryId, claimToken, NotificationDeliveryStatus.IN_FLIGHT.name());
    }

    @Override
    public int returnToQueued(UUID deliveryId, UUID claimToken, java.time.OffsetDateTime failedAt,
                              String failureCode) {
        return jdbc.update("UPDATE notification_deliveries SET status = ?, last_failure_at = ?, failure_code = ?, "
                        + "in_flight_at = NULL, lease_until = NULL, claim_token = NULL, updated_at = ? "
                        + "WHERE id = ? AND claim_token = ? AND status = ?", NotificationDeliveryStatus.QUEUED.name(),
                failedAt, failureCode, failedAt, deliveryId, claimToken, NotificationDeliveryStatus.IN_FLIGHT.name());
    }

    @Override
    public int markFailed(UUID deliveryId, UUID claimToken, java.time.OffsetDateTime failedAt,
                          String failureCode) {
        return jdbc.update("UPDATE notification_deliveries SET status = ?, last_failure_at = ?, failure_code = ?, "
                        + "in_flight_at = NULL, lease_until = NULL, claim_token = NULL, updated_at = ? "
                        + "WHERE id = ? AND claim_token = ? AND status = ?", NotificationDeliveryStatus.FAILED.name(),
                failedAt, failureCode, failedAt, deliveryId, claimToken, NotificationDeliveryStatus.IN_FLIGHT.name());
    }

    private PersistenceModels.NotificationDelivery map(java.sql.ResultSet r, int rowNumber)
            throws java.sql.SQLException {
        return new PersistenceModels.NotificationDelivery(r.getObject("id", UUID.class),
                r.getObject("notification_id", UUID.class), NotificationChannel.fromValue(r.getString("channel")),
                NotificationDeliveryStatus.fromValue(r.getString("status")),
                r.getString("destination"), r.getInt("attempt_count"), r.getObject("in_flight_at", java.time.OffsetDateTime.class),
                r.getObject("lease_until", java.time.OffsetDateTime.class), r.getObject("claim_token", UUID.class),
                r.getObject("last_attempt_at", java.time.OffsetDateTime.class), r.getObject("delivered_at", java.time.OffsetDateTime.class),
                r.getObject("last_failure_at", java.time.OffsetDateTime.class), r.getString("failure_code"),
                r.getObject("created_at", java.time.OffsetDateTime.class), r.getObject("updated_at", java.time.OffsetDateTime.class));
    }
}

@Repository("processingAttemptRepository")
@Profile("!test")
class JdbcProcessingAttemptRepository implements RepositoryPorts.ProcessingAttemptRepository {
    private final JdbcTemplate jdbc;

    JdbcProcessingAttemptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void save(PersistenceModels.ProcessingAttempt attempt) {
        jdbc.update("INSERT INTO processing_attempts (id, document_id, attempt_number, status, started_at, completed_at, error_code) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)", attempt.id(), attempt.documentId(), attempt.attemptNumber(), attempt.status().value(),
                attempt.startedAt(), attempt.completedAt(), attempt.errorCode());
    }

    public Optional<PersistenceModels.ProcessingAttempt> findById(UUID id) {
        List<PersistenceModels.ProcessingAttempt> rows = jdbc.query("SELECT id, document_id, attempt_number, status, started_at, completed_at, error_code "
                + "FROM processing_attempts WHERE id = ?", (r, n) -> new PersistenceModels.ProcessingAttempt(r.getObject("id", UUID.class),
                r.getObject("document_id", UUID.class), r.getInt("attempt_number"), ProcessingAttemptStatus.fromValue(r.getString("status")),
                r.getObject("started_at", java.time.OffsetDateTime.class), r.getObject("completed_at", java.time.OffsetDateTime.class),
                r.getString("error_code")), id);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<PersistenceModels.ProcessingAttempt> findLatestByDocumentId(UUID documentId) {
        return findOne("WHERE document_id = ? ORDER BY attempt_number DESC LIMIT 1", documentId);
    }

    @Override
    public Optional<PersistenceModels.ProcessingAttempt> findRunningByDocumentId(UUID documentId) {
        return findOne("WHERE document_id = ? AND status = '" + ProcessingAttemptStatus.RUNNING.value() + "'", documentId);
    }

    @Override
    public int finalize(UUID id, ProcessingAttemptStatus status, String errorCode) {
        return jdbc.update("UPDATE processing_attempts SET status = ?, completed_at = CURRENT_TIMESTAMP, error_code = ? "
                + "WHERE id = ? AND status = ?", status.value(), errorCode, id, ProcessingAttemptStatus.RUNNING.value());
    }

    @Override
    public int finalizeRunningByDocumentId(UUID documentId, String errorCode) {
        return jdbc.update("UPDATE processing_attempts SET status = ?, completed_at = CURRENT_TIMESTAMP, error_code = ? "
                + "WHERE document_id = ? AND status = ?", ProcessingAttemptStatus.FAILED.value(), errorCode, documentId,
                ProcessingAttemptStatus.RUNNING.value());
    }

    private Optional<PersistenceModels.ProcessingAttempt> findOne(String predicate, UUID documentId) {
        List<PersistenceModels.ProcessingAttempt> rows = jdbc.query("SELECT id, document_id, attempt_number, status, "
                + "started_at, completed_at, error_code FROM processing_attempts " + predicate, (r, n) ->
                new PersistenceModels.ProcessingAttempt(r.getObject("id", UUID.class), r.getObject("document_id", UUID.class),
                        r.getInt("attempt_number"), ProcessingAttemptStatus.fromValue(r.getString("status")), r.getObject("started_at", java.time.OffsetDateTime.class),
                        r.getObject("completed_at", java.time.OffsetDateTime.class), r.getString("error_code")), documentId);
        return rows.stream().findFirst();
    }
}

@Repository("uploadRepository")
@Profile("!test")
class JdbcUploadRepository implements RepositoryPorts.UploadRepository {
    private final JdbcTemplate jdbc;

    JdbcUploadRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void save(PersistenceModels.Upload upload) {
        jdbc.update("INSERT INTO uploads (id, user_id, status, object_key, original_filename, contract_type, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                upload.id(), upload.userId(), upload.status().value(), upload.objectKey(), upload.originalFilename(),
                upload.contractType().value(), upload.expiresAt(), upload.createdAt());
    }

    public Optional<PersistenceModels.Upload> findById(UUID id) {
        List<PersistenceModels.Upload> rows = jdbc.query("SELECT id, user_id, status, object_key, original_filename, contract_type, expires_at, created_at FROM uploads WHERE id = ?",
                (r, n) -> new PersistenceModels.Upload(r.getObject("id", UUID.class), r.getObject("user_id", UUID.class), UploadStatus.valueOf(r.getString("status")),
                        r.getString("object_key"), r.getString("original_filename"), ContractType.fromValue(r.getString("contract_type")),
                        r.getObject("expires_at", java.time.OffsetDateTime.class),
                        r.getObject("created_at", java.time.OffsetDateTime.class)), id);
        return rows.stream().findFirst();
    }

    public Optional<PersistenceModels.Upload> findByIdForUpdate(UUID id) {
        List<PersistenceModels.Upload> rows = jdbc.query("SELECT id, user_id, status, object_key, original_filename, contract_type, expires_at, created_at FROM uploads WHERE id = ? FOR UPDATE",
                (r, n) -> new PersistenceModels.Upload(r.getObject("id", UUID.class), r.getObject("user_id", UUID.class), UploadStatus.valueOf(r.getString("status")),
                        r.getString("object_key"), r.getString("original_filename"), ContractType.fromValue(r.getString("contract_type")),
                        r.getObject("expires_at", java.time.OffsetDateTime.class),
                        r.getObject("created_at", java.time.OffsetDateTime.class)), id);
        return rows.stream().findFirst();
    }

    public int markUploaded(UUID id, String expectedObjectKey) {
        return jdbc.update("UPDATE uploads SET status = ? WHERE id = ? AND object_key = ? AND status = ?",
                UploadStatus.UPLOADED.value(), id, expectedObjectKey, UploadStatus.UPLOADING.value());
    }

    public int markScanned(UUID id) {
        return jdbc.update("UPDATE uploads SET status = ? WHERE id = ? AND status = ?",
                UploadStatus.SCANNED.value(), id, UploadStatus.UPLOADED.value());
    }

    public int markPromoted(UUID id) {
        return jdbc.update("UPDATE uploads SET status = ? WHERE id = ? AND status = ?",
                UploadStatus.PROMOTED.value(), id, UploadStatus.SCANNED.value());
    }

    public int markCompleted(UUID id) {
        return jdbc.update("UPDATE uploads SET status = ? WHERE id = ? AND status = ?",
                UploadStatus.COMPLETED.value(), id, UploadStatus.PROMOTED.value());
    }

    public int markRejected(UUID id, String expectedObjectKey) {
        return jdbc.update("UPDATE uploads SET status = ? WHERE id = ? AND object_key = ? AND status IN (?, ?)",
                UploadStatus.REJECTED.value(), id, expectedObjectKey,
                UploadStatus.UPLOADING.value(), UploadStatus.UPLOADED.value());
    }

}

@Repository("auditEventRepository")
@Profile("!test")
class JdbcAuditEventRepository implements RepositoryPorts.AuditEventRepository {
    private final JdbcTemplate jdbc;

    JdbcAuditEventRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void append(PersistenceModels.AuditEvent event) {
        jdbc.update("INSERT INTO audit_events (id, user_id, resource_type, resource_id, action, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                event.id(), event.userId(), event.resourceType(), event.resourceId(), event.action(), event.createdAt());
    }
}

@Repository("usageRecordRepository")
@Profile("!test")
class JdbcUsageRecordRepository implements RepositoryPorts.UsageRecordRepository {
    private final JdbcTemplate jdbc;

    JdbcUsageRecordRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void save(PersistenceModels.UsageRecord record) {
        jdbc.update(connection -> {
                var statement = connection.prepareStatement("INSERT INTO usage_records "
                        + "(id, user_id, document_id, operation, provider, service, usage_data, cost_amount, currency, usage_event_id, created_at, cost_status, pricing_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                statement.setObject(1, record.id());
                statement.setObject(2, record.userId());
                statement.setObject(3, record.documentId());
                statement.setString(4, record.operation());
                statement.setString(5, record.provider());
                statement.setString(6, record.service());
                statement.setObject(7, JdbcJsonb.value(record.usageData()));
                statement.setBigDecimal(8, record.costAmount());
                statement.setString(9, record.currency());
                statement.setObject(10, record.usageEventId());
                statement.setObject(11, record.createdAt());
                statement.setString(12, record.costStatus().name());
                statement.setString(13, record.pricingVersion());
                return statement;
            });
    }

    public int saveIfAbsent(PersistenceModels.UsageRecord record) {
        return jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO usage_records "
                    + "(id, user_id, document_id, operation, provider, service, usage_data, cost_amount, currency, usage_event_id, created_at, cost_status, pricing_version) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (usage_event_id) DO NOTHING");
            statement.setObject(1, record.id()); statement.setObject(2, record.userId()); statement.setObject(3, record.documentId());
            statement.setString(4, record.operation()); statement.setString(5, record.provider()); statement.setString(6, record.service());
            statement.setObject(7, JdbcJsonb.value(record.usageData())); statement.setBigDecimal(8, record.costAmount());
            statement.setString(9, record.currency()); statement.setObject(10, record.usageEventId()); statement.setObject(11, record.createdAt());
            statement.setString(12, record.costStatus().name()); statement.setString(13, record.pricingVersion());
            return statement;
        });
    }

    public Optional<PersistenceModels.UsageRecord> findByUsageEventId(UUID usageEventId) {
        List<PersistenceModels.UsageRecord> rows = jdbc.query("SELECT id, user_id, document_id, operation, provider, service, usage_data, cost_amount, currency, usage_event_id, created_at, cost_status, pricing_version "
                + "FROM usage_records WHERE usage_event_id = ?", (r, n) -> new PersistenceModels.UsageRecord(r.getObject("id", UUID.class),
                r.getObject("user_id", UUID.class), r.getObject("document_id", UUID.class), r.getString("operation"),
                r.getString("provider"), r.getString("service"), JdbcJsonb.read(r.getObject("usage_data")), r.getBigDecimal("cost_amount"),
                r.getString("currency"), r.getObject("usage_event_id", UUID.class), r.getObject("created_at", java.time.OffsetDateTime.class),
                io.contractforge.backend.domain.model.UsageCostStatus.valueOf(r.getString("cost_status")), r.getString("pricing_version")), usageEventId);
        return rows.stream().findFirst();
    }

}
