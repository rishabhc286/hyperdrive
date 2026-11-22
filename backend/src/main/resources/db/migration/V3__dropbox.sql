-- Batch 4: Dropbox as a second storage provider.
-- Reuses connected_accounts / provider_credentials / storage_connection_transactions; adds only the
-- typed root information Dropbox needs. Uniqueness stays (user_id, provider, provider_account_id).

ALTER TABLE connected_accounts DROP CONSTRAINT connected_accounts_provider_check;
ALTER TABLE connected_accounts ADD CONSTRAINT connected_accounts_provider_check
    CHECK (provider IN ('GOOGLE_DRIVE', 'DROPBOX'));

ALTER TABLE storage_connection_transactions DROP CONSTRAINT storage_connection_transactions_provider_check;
ALTER TABLE storage_connection_transactions ADD CONSTRAINT storage_connection_transactions_provider_check
    CHECK (provider IN ('GOOGLE_DRIVE', 'DROPBOX'));

-- Root of an account. Google Drive: the My Drive root folder id. Dropbox: the account's root
-- namespace id (root_info.root_namespace_id), sent as Dropbox-API-Path-Root; there is no folder id.
ALTER TABLE connected_accounts ALTER COLUMN root_folder_id DROP NOT NULL;
ALTER TABLE connected_accounts ADD COLUMN root_namespace_id varchar(64)
    CHECK (root_namespace_id IS NULL OR root_namespace_id ~ '^[0-9]+$');
-- Dropbox root_info tag: USER (personal or member folder is the root) or TEAM (team space root).
ALTER TABLE connected_accounts ADD COLUMN root_type varchar(8)
    CHECK (root_type IS NULL OR root_type IN ('USER', 'TEAM'));
ALTER TABLE connected_accounts ADD CONSTRAINT connected_accounts_root_by_provider
    CHECK ((provider = 'GOOGLE_DRIVE' AND root_folder_id IS NOT NULL AND root_namespace_id IS NULL AND root_type IS NULL)
        OR (provider = 'DROPBOX' AND root_folder_id IS NULL AND root_namespace_id IS NOT NULL AND root_type IS NOT NULL));

-- Dropbox consent does not use PKCE (its use together with a client secret is not documented), so the
-- encrypted verifier is required only for Google Drive attempts.
ALTER TABLE storage_connection_transactions DROP CONSTRAINT storage_tx_started_has_state;
ALTER TABLE storage_connection_transactions ADD CONSTRAINT storage_tx_started_has_state
    CHECK (status NOT IN ('AUTHORIZING', 'COMPLETING')
        OR (oauth_state_hash IS NOT NULL AND browser_binding_hash IS NOT NULL AND browser_started_at IS NOT NULL
            AND (provider <> 'GOOGLE_DRIVE' OR encrypted_code_verifier IS NOT NULL)));
