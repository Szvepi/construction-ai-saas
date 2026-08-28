ALTER TABLE email_drafts
    ADD COLUMN IF NOT EXISTS line_items TEXT,
    ADD COLUMN IF NOT EXISTS review_warnings TEXT,
    ADD COLUMN IF NOT EXISTS unmapped_requests TEXT,
    ADD COLUMN IF NOT EXISTS client_name VARCHAR(255);

ALTER TABLE email_drafts
    DROP CONSTRAINT IF EXISTS email_drafts_status_check;

ALTER TABLE email_drafts
    ADD CONSTRAINT email_drafts_status_check CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'READY', 'SENT'));
