CREATE INDEX uploads_owner_collection_idx
    ON uploads (user_id, created_at DESC, id DESC);

CREATE INDEX documents_owner_collection_idx
    ON documents (user_id, created_at DESC, id DESC);

CREATE INDEX contract_parties_contract_role_idx
    ON contract_parties (contract_id, role, id);

CREATE INDEX events_owner_contract_history_idx
    ON events (user_id, document_id, event_date DESC, id DESC);

CREATE INDEX recommendations_owner_contract_history_idx
    ON recommendations (user_id, document_id, created_at DESC, id DESC);
