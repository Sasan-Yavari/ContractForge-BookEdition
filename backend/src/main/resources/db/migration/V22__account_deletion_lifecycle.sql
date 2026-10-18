ALTER TABLE audit_events DROP CONSTRAINT audit_events_resource_type_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_resource_type_check CHECK (
    resource_type IN ('USER', 'DOCUMENT', 'CONTRACT', 'EVENT', 'RECOMMENDATION', 'UPLOAD', 'ACCOUNT_DELETION')
);

CREATE TABLE account_deletion_jobs (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE,
    phase VARCHAR(40) NOT NULL,
    upload_capabilities_expire_at TIMESTAMPTZ NOT NULL,
    identity_fence_until TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    enqueued_at TIMESTAMPTZ,
    dispatch_token UUID,
    dispatch_lease_until TIMESTAMPTZ,
    worker_token UUID,
    worker_lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT account_deletion_jobs_user_fk FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT account_deletion_jobs_phase_check CHECK (
        phase IN ('DISABLE_COGNITO', 'WAIT_UPLOAD_CAPABILITIES', 'PURGE_RELATIONAL',
                  'DELETE_OBJECTS', 'DELETE_COGNITO', 'WAIT_IDENTITY_FENCE')
    ),
    CONSTRAINT account_deletion_jobs_dispatch_lease_check CHECK (
        (dispatch_token IS NULL) = (dispatch_lease_until IS NULL)
    ),
    CONSTRAINT account_deletion_jobs_worker_lease_check CHECK (
        (worker_token IS NULL) = (worker_lease_until IS NULL)
    )
);

CREATE INDEX account_deletion_jobs_dispatch_idx
    ON account_deletion_jobs (available_at, created_at)
    WHERE enqueued_at IS NULL;

CREATE TABLE account_deletion_objects (
    deletion_id UUID NOT NULL,
    object_key TEXT NOT NULL,
    object_kind VARCHAR(20) NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT account_deletion_objects_pk PRIMARY KEY (deletion_id, object_key),
    CONSTRAINT account_deletion_objects_job_fk
        FOREIGN KEY (deletion_id) REFERENCES account_deletion_jobs(id) ON DELETE CASCADE,
    CONSTRAINT account_deletion_objects_kind_check CHECK (object_kind IN ('QUARANTINE', 'TRUSTED'))
);

CREATE INDEX account_deletion_objects_pending_idx
    ON account_deletion_objects (deletion_id, object_key)
    WHERE deleted_at IS NULL;

CREATE FUNCTION cf_require_active_account(p_user_id UUID) RETURNS VOID
LANGUAGE plpgsql AS $$
DECLARE
    account_status VARCHAR(20);
BEGIN
    SELECT status INTO account_status FROM users WHERE id = p_user_id FOR SHARE;
    IF account_status IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'Account is not active' USING ERRCODE = '55000';
    END IF;
END;
$$;

CREATE FUNCTION cf_guard_direct_account_write() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM cf_require_active_account(NEW.user_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION cf_guard_document_account_write() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    owner_id UUID;
BEGIN
    SELECT user_id INTO owner_id FROM documents WHERE id = NEW.document_id;
    PERFORM cf_require_active_account(owner_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION cf_guard_contract_account_write() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    owner_id UUID;
BEGIN
    SELECT user_id INTO owner_id FROM documents WHERE id = NEW.contract_id;
    PERFORM cf_require_active_account(owner_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION cf_guard_notification_account_write() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    owner_id UUID;
BEGIN
    SELECT user_id INTO owner_id FROM recommendations WHERE id = NEW.recommendation_id;
    PERFORM cf_require_active_account(owner_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION cf_guard_delivery_account_write() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    owner_id UUID;
BEGIN
    SELECT r.user_id INTO owner_id
    FROM notifications n
    JOIN recommendations r ON r.id = n.recommendation_id
    WHERE n.id = NEW.notification_id;
    PERFORM cf_require_active_account(owner_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION cf_guard_identity_account_write() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (
        NEW.user_id IS DISTINCT FROM OLD.user_id
        OR NEW.provider IS DISTINCT FROM OLD.provider
        OR NEW.provider_subject IS DISTINCT FROM OLD.provider_subject
    ) THEN
        RAISE EXCEPTION 'External identity mapping is immutable' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.email IS NULL THEN
        RETURN NEW;
    END IF;
    PERFORM cf_require_active_account(NEW.user_id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER user_identities_active_account_write
    BEFORE INSERT OR UPDATE ON user_identities
    FOR EACH ROW EXECUTE FUNCTION cf_guard_identity_account_write();

CREATE TRIGGER documents_active_account_write
    BEFORE INSERT OR UPDATE ON documents
    FOR EACH ROW EXECUTE FUNCTION cf_guard_direct_account_write();
CREATE TRIGGER uploads_active_account_write
    BEFORE INSERT OR UPDATE ON uploads
    FOR EACH ROW EXECUTE FUNCTION cf_guard_direct_account_write();
CREATE TRIGGER events_active_account_write
    BEFORE INSERT OR UPDATE ON events
    FOR EACH ROW EXECUTE FUNCTION cf_guard_direct_account_write();
CREATE TRIGGER recommendations_active_account_write
    BEFORE INSERT OR UPDATE ON recommendations
    FOR EACH ROW EXECUTE FUNCTION cf_guard_direct_account_write();
CREATE TRIGGER usage_records_active_account_write
    BEFORE INSERT OR UPDATE ON usage_records
    FOR EACH ROW EXECUTE FUNCTION cf_guard_direct_account_write();
CREATE TRIGGER audit_events_active_account_write
    BEFORE INSERT OR UPDATE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION cf_guard_direct_account_write();

CREATE TRIGGER processing_attempts_active_account_write
    BEFORE INSERT OR UPDATE ON processing_attempts
    FOR EACH ROW EXECUTE FUNCTION cf_guard_document_account_write();
CREATE TRIGGER contracts_active_account_write
    BEFORE INSERT OR UPDATE ON contracts
    FOR EACH ROW EXECUTE FUNCTION cf_guard_document_account_write();
CREATE TRIGGER contract_parties_active_account_write
    BEFORE INSERT OR UPDATE ON contract_parties
    FOR EACH ROW EXECUTE FUNCTION cf_guard_contract_account_write();
CREATE TRIGGER contract_terms_active_account_write
    BEFORE INSERT OR UPDATE ON contract_terms
    FOR EACH ROW EXECUTE FUNCTION cf_guard_contract_account_write();
CREATE TRIGGER notifications_active_account_write
    BEFORE INSERT OR UPDATE ON notifications
    FOR EACH ROW EXECUTE FUNCTION cf_guard_notification_account_write();
CREATE TRIGGER notification_deliveries_active_account_write
    BEFORE INSERT OR UPDATE ON notification_deliveries
    FOR EACH ROW EXECUTE FUNCTION cf_guard_delivery_account_write();
