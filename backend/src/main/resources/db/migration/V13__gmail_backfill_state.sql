ALTER TABLE gmail_connections
    ADD COLUMN backfill_page_token TEXT,
    ADD COLUMN backfill_query TEXT,
    ADD COLUMN backfill_completed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN backfill_lock_id UUID,
    ADD COLUMN backfill_lock_until TIMESTAMPTZ;
