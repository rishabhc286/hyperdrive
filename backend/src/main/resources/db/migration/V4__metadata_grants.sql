-- Batch 8: short-lived, metadata-only grants for the local agent (virtual drive browsing).
-- Only the SHA-256 hash of the opaque grant is stored. A grant belongs to one device session and
-- dies with it (logout, revocation, expiry, or cascade delete of the session).
CREATE TABLE metadata_grants (
    token_hash bytea       PRIMARY KEY,
    session_id uuid        NOT NULL REFERENCES device_sessions (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CHECK (expires_at > created_at)
);

CREATE INDEX metadata_grants_session_idx ON metadata_grants (session_id);
CREATE INDEX metadata_grants_expires_idx ON metadata_grants (expires_at);
