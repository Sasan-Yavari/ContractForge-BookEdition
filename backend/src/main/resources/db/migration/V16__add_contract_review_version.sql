ALTER TABLE contracts ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE contracts ADD CONSTRAINT contracts_version_check CHECK (version >= 0);
