CREATE TABLE recommendations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    document_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL,
    data JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    CONSTRAINT recommendations_user_fk
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT recommendations_document_fk
        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    CONSTRAINT recommendations_type_check CHECK (type IN (
        'PRICE_OPPORTUNITY', 'CONTRACT_EXPIRY', 'RENEWAL',
        'TERMS_CHANGE', 'DISCOUNT_EXPIRY', 'ACTION_REQUIRED'
    ))
);
