CREATE TABLE events (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    document_id UUID NOT NULL,
    type VARCHAR(40) NOT NULL,
    description TEXT NOT NULL,
    event_date TIMESTAMPTZ NOT NULL,
    amount NUMERIC(15,2),
    currency VARCHAR(3),
    status VARCHAR(20) NOT NULL,
    source_reference TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT events_user_fk
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT events_document_fk
        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
    CONSTRAINT events_type_check CHECK (type IN (
        'PAYMENT_DUE', 'CONTRACT_EXPIRING', 'CONTRACT_RENEWING',
        'CANCELLATION_DEADLINE', 'PRICE_CHANGE', 'WARRANTY_EXPIRING',
        'REQUIREMENT', 'OTHER'
    )),
    CONSTRAINT events_status_check CHECK (status IN ('ACTIVE', 'COMPLETED', 'DISMISSED', 'CANCELLED')),
    CONSTRAINT events_amount_check CHECK (amount IS NULL OR amount >= 0),
    CONSTRAINT events_currency_check CHECK ((amount IS NULL AND currency IS NULL) OR (amount IS NOT NULL AND currency IS NOT NULL))
);
