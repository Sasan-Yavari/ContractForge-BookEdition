CREATE TABLE usage_records (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    document_id UUID NOT NULL,
    operation VARCHAR(100) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    service VARCHAR(100) NOT NULL,
    usage_data JSONB NOT NULL,
    cost_amount NUMERIC(15,4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    usage_event_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT usage_records_user_fk
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT usage_records_document_fk
        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    CONSTRAINT usage_records_cost_check CHECK (cost_amount >= 0),
    CONSTRAINT usage_records_usage_event_id_unique UNIQUE (usage_event_id)
);

CREATE INDEX usage_records_document_id_idx
    ON usage_records (document_id);

CREATE INDEX usage_records_user_created_at_idx
    ON usage_records (user_id, created_at DESC);

CREATE INDEX usage_records_provider_created_at_idx
    ON usage_records (provider, created_at DESC);
