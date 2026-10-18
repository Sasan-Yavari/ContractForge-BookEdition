ALTER TABLE contracts DROP CONSTRAINT contracts_status_check;
ALTER TABLE contracts ADD CONSTRAINT contracts_status_check
    CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'));
