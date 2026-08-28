-- Új oszlopok felvétele
ALTER TABLE email_drafts
    ADD COLUMN line_items       TEXT,
    ADD COLUMN review_warnings  TEXT,
    ADD COLUMN unmapped_requests TEXT;

-- 1. Régi CHECK constraint eldobása
ALTER TABLE email_drafts
DROP CONSTRAINT email_drafts_status_check;

-- 2. Új CHECK constraint hozzáadása a kibővített értékekkel
ALTER TABLE email_drafts
    ADD CONSTRAINT email_drafts_status_check
        CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'READY', 'SENT'));