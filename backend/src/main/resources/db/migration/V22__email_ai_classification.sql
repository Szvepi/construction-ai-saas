ALTER TABLE emails
    DROP CONSTRAINT emails_category_check,
    ADD CONSTRAINT emails_category_check
        CHECK (category IN ('QUOTE_REQUEST', 'SPAM', 'OTHER', 'CLASSIFICATION_REVIEW')),
    ADD COLUMN classification_metadata TEXT,
    ADD COLUMN classification_status VARCHAR(20) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN classification_source VARCHAR(20) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN classification_version VARCHAR(32),
    ADD COLUMN classification_reason VARCHAR(500),
    ADD COLUMN classification_evidence VARCHAR(500),
    ADD COLUMN classification_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN classification_retry_at TIMESTAMPTZ,
    ADD COLUMN classification_last_attempt_at TIMESTAMPTZ,
    ADD CONSTRAINT emails_classification_status_check
        CHECK (classification_status IN ('PENDING', 'RETRY', 'PROCESSING', 'CLASSIFIED', 'REVIEW', 'FAILED', 'LEGACY')),
    ADD CONSTRAINT emails_classification_source_check
        CHECK (classification_source IN ('PENDING', 'AI', 'MANUAL', 'LEGACY')),
    ADD CONSTRAINT emails_classification_attempts_check
        CHECK (classification_attempts >= 0);

CREATE INDEX idx_emails_classification_due
    ON emails (classification_status, classification_retry_at, email_received_at, id);

CREATE TABLE email_classification_daily_budget
(
    budget_date DATE PRIMARY KEY,
    attempts    INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0)
);
