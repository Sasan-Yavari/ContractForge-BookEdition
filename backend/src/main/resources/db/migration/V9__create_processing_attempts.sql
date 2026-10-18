CREATE TABLE processing_attempts (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    error_code VARCHAR(100),
    CONSTRAINT processing_attempts_document_fk
        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    CONSTRAINT processing_attempts_attempt_number_check CHECK (attempt_number > 0),
    CONSTRAINT processing_attempts_status_check CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT processing_attempts_unique UNIQUE (document_id, attempt_number)
);
