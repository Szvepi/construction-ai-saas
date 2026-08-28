-- Add JSON/text column to store AI unmapped requests for drafts
ALTER TABLE email_drafts
    ADD COLUMN unmapped_requests TEXT;
