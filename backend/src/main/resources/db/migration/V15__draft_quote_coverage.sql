ALTER TABLE email_drafts
    ADD COLUMN IF NOT EXISTS requested_work_items TEXT,
    ADD COLUMN IF NOT EXISTS coverage_decisions TEXT,
    ADD COLUMN IF NOT EXISTS acknowledged_warnings TEXT,
    ADD COLUMN IF NOT EXISTS analysis_fingerprint VARCHAR(64),
    ADD COLUMN IF NOT EXISTS draft_version BIGINT NOT NULL DEFAULT 0;
