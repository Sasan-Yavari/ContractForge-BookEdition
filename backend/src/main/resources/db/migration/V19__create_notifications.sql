ALTER TABLE user_identities
    ADD COLUMN email VARCHAR(320);

CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    recommendation_id UUID NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    data JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT notifications_recommendation_fk
        FOREIGN KEY (recommendation_id) REFERENCES recommendations(id) ON DELETE CASCADE,
    CONSTRAINT notifications_recommendation_unique
        UNIQUE (recommendation_id),
    CONSTRAINT notifications_type_check
        CHECK (notification_type IN ('RECOMMENDATION'))
);

CREATE TABLE notification_deliveries (
    id UUID PRIMARY KEY,
    notification_id UUID NOT NULL,
    channel VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    destination VARCHAR(320),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    in_flight_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    claim_token UUID,
    last_attempt_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    failure_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT notification_deliveries_notification_fk
        FOREIGN KEY (notification_id) REFERENCES notifications(id) ON DELETE CASCADE,
    CONSTRAINT notification_deliveries_notification_channel_unique
        UNIQUE (notification_id, channel),
    CONSTRAINT notification_deliveries_channel_check
        CHECK (channel IN ('EMAIL')),
    CONSTRAINT notification_deliveries_status_check
        CHECK (status IN ('QUEUED', 'IN_FLIGHT', 'DELIVERED', 'FAILED')),
    CONSTRAINT notification_deliveries_attempt_count_check
        CHECK (attempt_count >= 0),
    CONSTRAINT notification_deliveries_destination_length_check
        CHECK (destination IS NULL OR length(destination) <= 320),
    CONSTRAINT notification_deliveries_failure_code_length_check
        CHECK (failure_code IS NULL OR length(failure_code) <= 100)
);
