CREATE UNIQUE INDEX events_monitoring_occurrence_unique_idx
    ON events(document_id, type, source_reference)
    WHERE source_reference IS NOT NULL;
