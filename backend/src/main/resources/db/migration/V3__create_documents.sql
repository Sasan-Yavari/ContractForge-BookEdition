CREATE TABLE documents (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    storage_key TEXT NOT NULL,
    filename TEXT NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    size BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT documents_user_fk
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT documents_size_check CHECK (size >= 0),
    CONSTRAINT documents_status_check CHECK (status IN ('PROCESSING', 'SUCCESS', 'FAILED'))
);
