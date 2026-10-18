CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id UUID NOT NULL,
    action VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT audit_events_user_fk
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT audit_events_resource_type_check CHECK (
        resource_type IN ('USER', 'DOCUMENT', 'CONTRACT', 'EVENT', 'RECOMMENDATION', 'UPLOAD')
    )
);
