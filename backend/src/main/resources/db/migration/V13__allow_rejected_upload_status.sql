ALTER TABLE uploads DROP CONSTRAINT uploads_status_check;

ALTER TABLE uploads ADD CONSTRAINT uploads_status_check
    CHECK (status IN ('UPLOADING', 'UPLOADED', 'SCANNED', 'PROMOTED', 'COMPLETED', 'REJECTED'));
