CREATE TABLE user_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    provider VARCHAR(50) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT user_identities_user_fk
        FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT user_identities_provider_subject_unique
        UNIQUE (provider, provider_subject)
);
