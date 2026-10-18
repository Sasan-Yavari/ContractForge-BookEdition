ALTER TABLE usage_records
    ADD COLUMN cost_status VARCHAR(20) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN pricing_version VARCHAR(100);

ALTER TABLE usage_records
    ADD CONSTRAINT usage_records_cost_status_check
        CHECK (cost_status IN ('CALCULATED', 'UNKNOWN', 'LEGACY')),
    ADD CONSTRAINT usage_records_pricing_version_check
        CHECK (cost_status <> 'CALCULATED' OR pricing_version IS NOT NULL),
    ADD CONSTRAINT usage_records_unknown_cost_check
        CHECK (cost_status <> 'UNKNOWN' OR (cost_amount = 0 AND pricing_version IS NULL));
