ALTER TABLE gmail_connections
    ADD COLUMN gmail_sync_retry_at TIMESTAMPTZ,
    ADD COLUMN gmail_sync_retry_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN gmail_sync_retry_exhausted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN gmail_sync_auth_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT gmail_connections_sync_retry_attempts_check
        CHECK (gmail_sync_retry_attempts >= 0);

CREATE INDEX idx_gmail_connections_sync_retry
    ON gmail_connections (gmail_sync_retry_at)
    WHERE gmail_sync_retry_at IS NOT NULL;
