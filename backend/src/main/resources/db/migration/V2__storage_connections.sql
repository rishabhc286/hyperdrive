-- Batch 3: cloud-storage connections (Google Drive), encrypted provider credentials and the
-- storage authorization transactions. Storage connections are separate from HyperDrive sign-in:
-- nothing here changes users, auth_identities or device_sessions.
-- Drive file listings are NOT stored; they are fetched on demand.

-- One row per storage account a HyperDrive user connected. Identified by the provider's stable
-- account identifier (for Google: the validated OIDC issuer + subject), never by email.
-- The same provider account may be connected by different HyperDrive users (each holds its own
-- credentials); within one user it is unique, so reconnecting updates the existing row.
CREATE TABLE connected_accounts (
    id                  uuid          PRIMARY KEY,
    user_id             uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider            varchar(32)   NOT NULL CHECK (provider IN ('GOOGLE_DRIVE')),
    provider_issuer     varchar(512)  NOT NULL,
    provider_account_id varchar(255)  NOT NULL,
    display_name        varchar(200),
    provider_email      varchar(320),
    root_folder_id      varchar(256)  NOT NULL CHECK (root_folder_id ~ '^[A-Za-z0-9_-]+$'),
    status              varchar(24)   NOT NULL CHECK (status IN ('CONNECTED', 'NEEDS_RECONNECT')),
    granted_scopes      varchar(2000) NOT NULL,
    connected_at        timestamptz   NOT NULL,
    updated_at          timestamptz   NOT NULL,
    last_success_at     timestamptz,
    last_error_code     varchar(64),
    CONSTRAINT connected_accounts_owner_provider_account_key
        UNIQUE (user_id, provider, provider_account_id)
);
CREATE INDEX connected_accounts_user_id_idx ON connected_accounts (user_id);

-- Provider OAuth tokens, encrypted with AES-256-GCM by the backend (key outside the database).
-- Each value is nonce (12 bytes) || ciphertext || tag (16 bytes); the ciphertext is bound to the
-- account id and field name, so values cannot be swapped between rows or columns.
CREATE TABLE provider_credentials (
    connected_account_id    uuid        PRIMARY KEY REFERENCES connected_accounts (id) ON DELETE CASCADE,
    encrypted_access_token  bytea       NOT NULL CHECK (octet_length(encrypted_access_token) > 28),
    encrypted_refresh_token bytea       CHECK (encrypted_refresh_token IS NULL OR octet_length(encrypted_refresh_token) > 28),
    access_token_expires_at timestamptz NOT NULL,
    encryption_key_version  integer     NOT NULL CHECK (encryption_key_version > 0),
    version                 bigint      NOT NULL DEFAULT 0,
    updated_at              timestamptz NOT NULL
);

-- Storage authorization transactions (system-browser consent for a storage provider).
--   PENDING      created by an authenticated desktop session; holds only a hashed one-time ticket
--   AUTHORIZING  the ticket was used once to start the browser leg; OAuth state is now bound
--   COMPLETING   Google called back once (claimed atomically; any repeat is rejected)
--   COMPLETED / FAILED / CANCELLED  final
CREATE TABLE storage_connection_transactions (
    id                      uuid        PRIMARY KEY,
    user_id                 uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_session_id       uuid        NOT NULL REFERENCES device_sessions (id) ON DELETE CASCADE,
    provider                varchar(32) NOT NULL CHECK (provider IN ('GOOGLE_DRIVE')),
    status                  varchar(16) NOT NULL
        CHECK (status IN ('PENDING', 'AUTHORIZING', 'COMPLETING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    start_ticket_hash       bytea       NOT NULL CHECK (octet_length(start_ticket_hash) = 32),
    ticket_expires_at       timestamptz NOT NULL,
    browser_started_at      timestamptz,
    oauth_state_hash        bytea       CHECK (oauth_state_hash IS NULL OR octet_length(oauth_state_hash) = 32),
    browser_binding_hash    bytea       CHECK (browser_binding_hash IS NULL OR octet_length(browser_binding_hash) = 32),
    -- The (already hashed) OIDC nonce sent to Google; compared with the ID token's nonce claim.
    oidc_nonce              varchar(128),
    encrypted_code_verifier bytea,
    encryption_key_version  integer,
    connected_account_id    uuid        REFERENCES connected_accounts (id) ON DELETE SET NULL,
    reconnected             boolean,
    error_code              varchar(64),
    created_at              timestamptz NOT NULL,
    expires_at              timestamptz NOT NULL,
    completed_at            timestamptz,
    CONSTRAINT storage_tx_ticket_key UNIQUE (start_ticket_hash),
    CONSTRAINT storage_tx_state_key UNIQUE (oauth_state_hash),
    CONSTRAINT storage_tx_started_has_state
        CHECK (status NOT IN ('AUTHORIZING', 'COMPLETING')
            OR (oauth_state_hash IS NOT NULL AND browser_binding_hash IS NOT NULL
                AND encrypted_code_verifier IS NOT NULL AND browser_started_at IS NOT NULL)),
    CONSTRAINT storage_tx_completed_has_account
        CHECK (status <> 'COMPLETED' OR (connected_account_id IS NOT NULL OR completed_at IS NOT NULL))
);
CREATE INDEX storage_tx_user_id_idx ON storage_connection_transactions (user_id);
CREATE INDEX storage_tx_expires_at_idx ON storage_connection_transactions (expires_at);
