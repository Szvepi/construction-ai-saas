ALTER TABLE email_drafts
    DROP COLUMN IF EXISTS customer_facing_notes,
    ADD COLUMN include_additional_notes BOOLEAN NOT NULL DEFAULT TRUE;
