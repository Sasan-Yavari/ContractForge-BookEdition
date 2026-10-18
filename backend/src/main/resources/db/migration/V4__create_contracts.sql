CREATE TABLE contracts (
    document_id UUID PRIMARY KEY,
    type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    data JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ,
    CONSTRAINT contracts_document_fk
        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    CONSTRAINT contracts_status_check CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT contracts_type_check CHECK (type IN ('ELECTRICITY'))
);
