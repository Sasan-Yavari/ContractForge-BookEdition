CREATE TABLE uploads (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    object_key TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uploads_user_fk
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uploads_status_check CHECK (status IN ('UPLOADING', 'UPLOADED', 'SCANNED', 'PROMOTED', 'COMPLETED'))
);
