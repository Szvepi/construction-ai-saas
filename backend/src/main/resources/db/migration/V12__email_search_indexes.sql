CREATE INDEX idx_emails_connection_received_id
    ON emails (gmail_connection_id, email_received_at DESC, id DESC);

CREATE INDEX idx_emails_connection_category_received_id
    ON emails (gmail_connection_id, category, email_received_at DESC, id DESC);

CREATE INDEX idx_email_drafts_email_created_id
    ON email_drafts (email_id, created_at DESC, id DESC);

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_emails_subject_trgm
    ON emails USING GIN (LOWER(subject) gin_trgm_ops);

CREATE INDEX idx_emails_from_address_trgm
    ON emails USING GIN (LOWER(from_address) gin_trgm_ops);
