-- Batch 2: HyperDrive accounts, Google identities, desktop login handoff and device sessions.
-- All secrets (handoff codes, access tokens, refresh tokens) are stored only as SHA-256 hashes.
-- No Google/provider tokens are stored.

CREATE TABLE users (
    id                      uuid         PRIMARY KEY,
    username                varchar(24),
    username_normalized     varchar(24),
    display_name            varchar(200),
    contact_email           varchar(320),
    onboarding_completed_at timestamptz,
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    version                 bigint       NOT NULL DEFAULT 0,
    CONSTRAINT users_username_format
        CHECK (username IS NULL OR username ~ '^[A-Za-z0-9_]{3,24}$'),
    CONSTRAINT users_username_normalized_matches
        CHECK ((username IS NULL AND username_normalized IS NULL)
            OR (username_normalized = lower(username))),
    CONSTRAINT users_onboarding_consistent
        CHECK ((username IS NULL) = (onboarding_completed_at IS NULL)),
    CONSTRAINT users_username_normalized_key UNIQUE (username_normalized)
);

-- External identity: validated OIDC issuer + subject. Email is never used for matching.
CREATE TABLE auth_identities (
    id         uuid         PRIMARY KEY,
    user_id    uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    issuer     varchar(512) NOT NULL,
    subject    varchar(255) NOT NULL,
    created_at timestamptz  NOT NULL,
    CONSTRAINT auth_identities_issuer_subject_key UNIQUE (issuer, subject)
);
CREATE INDEX auth_identities_user_id_idx ON auth_identities (user_id);

-- One row per signed-in desktop installation. The session is the refresh-token family:
-- token_family_id is a stable identifier for the rotation chain, 1:1 with the session.
CREATE TABLE device_sessions (
    id                         uuid        PRIMARY KEY,
    user_id                    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    device_name                varchar(64),
    current_refresh_token_hash bytea,
    token_family_id            uuid        NOT NULL,
    created_at                 timestamptz NOT NULL,
    last_used_at               timestamptz NOT NULL,
    expires_at                 timestamptz NOT NULL,
    revoked_at                 timestamptz,
    revoked_reason             varchar(32),
    CONSTRAINT device_sessions_refresh_hash_key UNIQUE (current_refresh_token_hash),
    CONSTRAINT device_sessions_token_family_key UNIQUE (token_family_id),
    CONSTRAINT device_sessions_refresh_hash_length
        CHECK (current_refresh_token_hash IS NULL OR octet_length(current_refresh_token_hash) = 32),
    CONSTRAINT device_sessions_revocation_consistent
        CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL)),
    CONSTRAINT device_sessions_active_has_refresh
        CHECK (revoked_at IS NOT NULL OR current_refresh_token_hash IS NOT NULL)
);
CREATE INDEX device_sessions_user_id_idx ON device_sessions (user_id);

-- Refresh tokens that were rotated away. Presenting one again is a replay and revokes the session.
CREATE TABLE superseded_refresh_tokens (
    token_hash    bytea       PRIMARY KEY CHECK (octet_length(token_hash) = 32),
    session_id    uuid        NOT NULL REFERENCES device_sessions (id) ON DELETE CASCADE,
    superseded_at timestamptz NOT NULL
);
CREATE INDEX superseded_refresh_tokens_session_id_idx ON superseded_refresh_tokens (session_id);

-- Short-lived opaque access tokens (hashes only).
CREATE TABLE access_tokens (
    token_hash bytea       PRIMARY KEY CHECK (octet_length(token_hash) = 32),
    session_id uuid        NOT NULL REFERENCES device_sessions (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX access_tokens_session_id_idx ON access_tokens (session_id);
CREATE INDEX access_tokens_expires_at_idx ON access_tokens (expires_at);

-- Desktop login transactions (system-browser sign-in handoff).
CREATE TABLE login_transactions (
    id                 uuid        PRIMARY KEY,
    code_challenge     varchar(43) NOT NULL CHECK (code_challenge ~ '^[A-Za-z0-9_-]{43}$'),
    callback_port      integer     NOT NULL CHECK (callback_port BETWEEN 1024 AND 65535),
    desktop_state      varchar(128) NOT NULL CHECK (desktop_state ~ '^[A-Za-z0-9_-]{22,128}$'),
    device_name        varchar(64),
    status             varchar(16) NOT NULL
        CHECK (status IN ('PENDING', 'AUTHENTICATED', 'CONSUMED', 'FAILED')),
    created_at         timestamptz NOT NULL,
    expires_at         timestamptz NOT NULL,
    browser_started_at timestamptz,
    user_id            uuid        REFERENCES users (id) ON DELETE CASCADE,
    handoff_code_hash  bytea       CHECK (handoff_code_hash IS NULL OR octet_length(handoff_code_hash) = 32),
    handoff_expires_at timestamptz,
    consumed_at        timestamptz,
    CONSTRAINT login_transactions_handoff_code_key UNIQUE (handoff_code_hash),
    CONSTRAINT login_transactions_authenticated_has_handoff
        CHECK (status NOT IN ('AUTHENTICATED', 'CONSUMED')
            OR (user_id IS NOT NULL AND handoff_code_hash IS NOT NULL AND handoff_expires_at IS NOT NULL))
);
CREATE INDEX login_transactions_expires_at_idx ON login_transactions (expires_at);
