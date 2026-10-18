CREATE TABLE contract_parties (
    id UUID PRIMARY KEY,
    contract_id UUID NOT NULL,
    role VARCHAR(20) NOT NULL,
    name TEXT NOT NULL,
    CONSTRAINT contract_parties_contract_fk
        FOREIGN KEY (contract_id) REFERENCES contracts(document_id) ON DELETE CASCADE,
    CONSTRAINT contract_parties_role_check CHECK (role IN ('CUSTOMER', 'PROVIDER', 'BROKER')),
    CONSTRAINT contract_parties_unique UNIQUE (contract_id, role, name)
);
