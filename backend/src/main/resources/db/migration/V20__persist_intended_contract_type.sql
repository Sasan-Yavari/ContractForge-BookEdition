ALTER TABLE uploads
    ADD COLUMN contract_type VARCHAR(50) NOT NULL DEFAULT 'ELECTRICITY',
    ADD CONSTRAINT uploads_contract_type_check CHECK (contract_type IN ('ELECTRICITY'));

ALTER TABLE documents
    ADD COLUMN contract_type VARCHAR(50) NOT NULL DEFAULT 'ELECTRICITY',
    ADD CONSTRAINT documents_contract_type_check CHECK (contract_type IN ('ELECTRICITY'));
