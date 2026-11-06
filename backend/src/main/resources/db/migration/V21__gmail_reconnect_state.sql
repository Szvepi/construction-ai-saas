ALTER TABLE gmail_connections
    ADD COLUMN gmail_sync_auth_error_category VARCHAR(64),
    ADD COLUMN gmail_sync_auth_required_at TIMESTAMPTZ;

UPDATE gmail_connections
SET gmail_sync_auth_error_category = 'UNKNOWN',
    gmail_sync_auth_required_at = NOW()
WHERE gmail_sync_auth_required = TRUE;
