ALTER TABLE emails
    ADD COLUMN classification_queue_type VARCHAR(24) NOT NULL DEFAULT 'HISTORICAL',
    ADD COLUMN classification_origin VARCHAR(24) NOT NULL DEFAULT 'HISTORICAL',
    ADD COLUMN classification_rule_id VARCHAR(64),
    ADD COLUMN classification_queued_at TIMESTAMPTZ,
    ADD COLUMN classification_started_at TIMESTAMPTZ,
    ADD COLUMN classification_completed_at TIMESTAMPTZ;

ALTER TABLE emails
    DROP CONSTRAINT emails_category_check,
    ADD CONSTRAINT emails_category_check
        CHECK (category IN ('QUOTE_REQUEST', 'SPAM', 'OTHER', 'CLASSIFICATION_REVIEW', 'UNCLASSIFIED')),
    DROP CONSTRAINT emails_classification_source_check,
    ADD CONSTRAINT emails_classification_source_check
        CHECK (classification_source IN ('PENDING', 'AI', 'RULE', 'MANUAL', 'LEGACY')),
    ADD CONSTRAINT emails_classification_queue_type_check
        CHECK (classification_queue_type IN ('FRESH', 'HISTORICAL', 'RECLASSIFICATION', 'RETRY')),
    ADD CONSTRAINT emails_classification_origin_check
        CHECK (classification_origin IN ('FRESH', 'HISTORICAL', 'RECLASSIFICATION'));

UPDATE emails
SET classification_queued_at = COALESCE(synced_at, email_received_at),
    classification_started_at = CASE
        WHEN classification_status = 'PROCESSING'
            THEN COALESCE(classification_last_attempt_at, synced_at, email_received_at)
        ELSE NULL
    END,
    classification_completed_at = CASE
        WHEN classification_status IN ('CLASSIFIED', 'REVIEW', 'FAILED', 'LEGACY')
            THEN COALESCE(classification_last_attempt_at, synced_at, email_received_at)
        ELSE NULL
    END,
    category = CASE
        WHEN classification_status IN ('PENDING', 'RETRY', 'PROCESSING') THEN 'UNCLASSIFIED'
        ELSE category
    END;

ALTER TABLE emails
    ALTER COLUMN classification_queued_at SET DEFAULT NOW(),
    ALTER COLUMN classification_queued_at SET NOT NULL;

CREATE INDEX idx_emails_classification_queue
    ON emails (classification_status, classification_queue_type, classification_queued_at, email_received_at, id);

ALTER TABLE email_classification_daily_budget
    ADD COLUMN successful_calls INTEGER NOT NULL DEFAULT 0 CHECK (successful_calls >= 0),
    ADD COLUMN failed_calls INTEGER NOT NULL DEFAULT 0 CHECK (failed_calls >= 0),
    ADD COLUMN classification_failures INTEGER NOT NULL DEFAULT 0 CHECK (classification_failures >= 0),
    ADD COLUMN prefiltered_emails INTEGER NOT NULL DEFAULT 0 CHECK (prefiltered_emails >= 0),
    ADD COLUMN input_tokens BIGINT NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    ADD COLUMN output_tokens BIGINT NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    ADD COLUMN estimated_cost_usd NUMERIC(18, 9) NOT NULL DEFAULT 0 CHECK (estimated_cost_usd >= 0),
    ADD COLUMN limit_hits INTEGER NOT NULL DEFAULT 0 CHECK (limit_hits >= 0);

CREATE TABLE email_classification_user_daily_budget
(
    budget_date              DATE           NOT NULL,
    user_id                  BIGINT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    attempts                 INTEGER        NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    successful_calls         INTEGER        NOT NULL DEFAULT 0 CHECK (successful_calls >= 0),
    failed_calls             INTEGER        NOT NULL DEFAULT 0 CHECK (failed_calls >= 0),
    classification_failures INTEGER        NOT NULL DEFAULT 0 CHECK (classification_failures >= 0),
    prefiltered_emails       INTEGER        NOT NULL DEFAULT 0 CHECK (prefiltered_emails >= 0),
    input_tokens             BIGINT         NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens            BIGINT         NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    estimated_cost_usd       NUMERIC(18, 9) NOT NULL DEFAULT 0 CHECK (estimated_cost_usd >= 0),
    limit_hits               INTEGER        NOT NULL DEFAULT 0 CHECK (limit_hits >= 0),
    last_claimed_at          TIMESTAMPTZ,
    PRIMARY KEY (budget_date, user_id)
);

CREATE INDEX idx_email_classification_user_budget_fairness
    ON email_classification_user_daily_budget (budget_date, last_claimed_at, user_id);

CREATE TABLE email_classification_ai_attempt
(
    id               BIGSERIAL PRIMARY KEY,
    email_id         BIGINT         NOT NULL REFERENCES emails (id) ON DELETE CASCADE,
    user_id          BIGINT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    budget_date      DATE           NOT NULL,
    attempt_number   INTEGER        NOT NULL CHECK (attempt_number > 0),
    queue_type       VARCHAR(24)    NOT NULL
        CHECK (queue_type IN ('FRESH', 'HISTORICAL', 'RECLASSIFICATION', 'RETRY')),
    origin_type      VARCHAR(24)    NOT NULL
        CHECK (origin_type IN ('FRESH', 'HISTORICAL', 'RECLASSIFICATION')),
    outcome          VARCHAR(16)    NOT NULL
        CHECK (outcome IN ('STARTED', 'SUCCEEDED', 'FAILED', 'STALE')),
    input_tokens     INTEGER        NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens    INTEGER        NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    estimated_cost_usd NUMERIC(18, 9) NOT NULL DEFAULT 0 CHECK (estimated_cost_usd >= 0),
    usage_reported   BOOLEAN        NOT NULL DEFAULT FALSE,
    failure_category VARCHAR(64),
    started_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    completed_at     TIMESTAMPTZ
);

CREATE INDEX idx_email_classification_ai_attempt_user_date
    ON email_classification_ai_attempt (user_id, budget_date, outcome);

CREATE INDEX idx_email_classification_ai_attempt_email
    ON email_classification_ai_attempt (email_id, started_at DESC);

ALTER TABLE emails
    ADD COLUMN classification_active_attempt_id BIGINT;
