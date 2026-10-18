CREATE TABLE contract_terms (
    contract_id UUID PRIMARY KEY,
    start_date DATE,
    end_date DATE,
    contract_duration INTEGER,
    renewal_type VARCHAR(30),
    pricing_model VARCHAR(30),
    electricity_data JSONB,
    CONSTRAINT contract_terms_contract_fk
        FOREIGN KEY (contract_id) REFERENCES contracts(document_id) ON DELETE CASCADE,
    CONSTRAINT contract_terms_duration_check
        CHECK (contract_duration IS NULL OR contract_duration > 0),
    CONSTRAINT contract_terms_renewal_type_check
        CHECK (renewal_type IS NULL OR renewal_type IN ('FIXED_TERM', 'AUTO_RENEW', 'OPEN_ENDED')),
    CONSTRAINT contract_terms_pricing_model_check
        CHECK (pricing_model IS NULL OR pricing_model IN ('FIXED', 'VARIABLE', 'DYNAMIC')),
    CONSTRAINT contract_terms_dates_check
        CHECK (start_date IS NULL OR end_date IS NULL OR end_date >= start_date)
);
