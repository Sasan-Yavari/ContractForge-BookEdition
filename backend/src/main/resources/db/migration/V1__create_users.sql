CREATE TABLE users (
    id UUID PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'DISABLED'))
);

INSERT INTO users (id, status, created_at, updated_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);
