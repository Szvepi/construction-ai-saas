CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE users
(
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE gmail_connections
(
    id                              BIGSERIAL PRIMARY KEY,
    user_id                         BIGINT       NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    gmail_address                   VARCHAR(255) NOT NULL,
    access_token_encrypted          TEXT         NOT NULL,
    refresh_token_encrypted         TEXT         NOT NULL,
    token_expires_at                TIMESTAMPTZ  NOT NULL,
    backfill_page_token             TEXT,
    backfill_query                  TEXT,
    backfill_completed              BOOLEAN      NOT NULL DEFAULT FALSE,
    backfill_lock_id                UUID,
    backfill_lock_until             TIMESTAMPTZ,
    backfill_cutoff                 TIMESTAMPTZ,
    backfill_policy_months          INTEGER,
    history_sync_cursor             TEXT,
    history_sync_phase              VARCHAR(32),
    history_sync_baseline_id        TEXT,
    history_sync_cutoff             TIMESTAMPTZ,
    history_sync_policy_months      INTEGER,
    history_sync_query              TEXT,
    history_sync_page_token         TEXT,
    history_sync_pending_page_token TEXT,
    history_sync_pending_history_id TEXT,
    history_sync_message_ids        TEXT,
    history_sync_message_offset     INTEGER      NOT NULL DEFAULT 0,
    history_sync_lock_id            UUID,
    history_sync_lock_until         TIMESTAMPTZ,
    CONSTRAINT gmail_connections_backfill_policy_months_check
        CHECK (backfill_policy_months IS NULL OR backfill_policy_months > 0),
    CONSTRAINT gmail_connections_history_policy_months_check
        CHECK (history_sync_policy_months IS NULL OR history_sync_policy_months > 0),
    CONSTRAINT gmail_connections_history_message_offset_check
        CHECK (history_sync_message_offset >= 0)
);

CREATE TABLE emails
(
    id                  BIGSERIAL PRIMARY KEY,
    gmail_connection_id BIGINT       NOT NULL REFERENCES gmail_connections (id) ON DELETE CASCADE,
    gmail_message_id    VARCHAR(255) NOT NULL,
    subject             VARCHAR(500),
    from_address        VARCHAR(255),
    body_text           TEXT,
    email_received_at   TIMESTAMPTZ  NOT NULL,
    synced_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    is_replied          BOOLEAN      NOT NULL DEFAULT FALSE,
    category            VARCHAR(50)  NOT NULL DEFAULT 'OTHER',
    CONSTRAINT emails_connection_message_unique UNIQUE (gmail_connection_id, gmail_message_id),
    CONSTRAINT emails_category_check CHECK (category IN ('QUOTE_REQUEST', 'SPAM', 'OTHER'))
);

CREATE TABLE email_drafts
(
    id                      BIGSERIAL PRIMARY KEY,
    draft_version           BIGINT      NOT NULL DEFAULT 0,
    email_id                BIGINT      NOT NULL REFERENCES emails (id) ON DELETE CASCADE,
    draft_body              TEXT        NOT NULL,
    client_name             VARCHAR(255),
    additional_notes        TEXT,
    include_additional_notes BOOLEAN     NOT NULL DEFAULT TRUE,
    status                  VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at                 TIMESTAMPTZ,
    review_warnings         TEXT,
    coverage_decisions      TEXT,
    acknowledged_warnings   TEXT,
    analysis_fingerprint    VARCHAR(64),
    quote_snapshot          TEXT,
    CONSTRAINT email_drafts_status_check
        CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'READY', 'SENT')),
    CONSTRAINT email_drafts_quote_snapshot_object_check
        CHECK (quote_snapshot IS NULL OR jsonb_typeof(quote_snapshot::jsonb) = 'object'),
    CONSTRAINT email_drafts_draft_version_check CHECK (draft_version >= 0)
);

CREATE TABLE company_profiles
(
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT       NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    company_name   VARCHAR(255) NOT NULL,
    legal_form     VARCHAR(255) NOT NULL,
    tax_number     VARCHAR(255) NOT NULL,
    eu_tax_number  VARCHAR(255),
    country        VARCHAR(100) NOT NULL DEFAULT 'Magyarország',
    postal_code    VARCHAR(255) NOT NULL,
    city           VARCHAR(255) NOT NULL,
    street_name    VARCHAR(255) NOT NULL,
    street_type    VARCHAR(100) NOT NULL,
    house_number   VARCHAR(50)  NOT NULL,
    building       VARCHAR(100),
    staircase      VARCHAR(100),
    floor_number   VARCHAR(50),
    door           VARCHAR(50),
    offeror_name   VARCHAR(255),
    offeror_phone  VARCHAR(100),
    vat_registered BOOLEAN      NOT NULL DEFAULT FALSE,
    tax_regime     VARCHAR(32)  NOT NULL DEFAULT 'AAM',
    vat_rate       NUMERIC(5, 2),
    offer_terms    TEXT         DEFAULT (
        'Jelen árajánlat a kiküldéstől számított 15 napig érvényes.' || E'\n\n' ||
        'A megadott árak a levélben leírt paraméterek alapján számított tájékoztató jellegű becslések. A végleges árajánlatot a helyszíni felmérést követően rögzítjük.'),
    CONSTRAINT company_profiles_tax_regime_check
        CHECK (tax_regime IN ('AAM', 'VAT_REGISTERED', 'REVERSE_CHARGE_UNSUPPORTED')),
    CONSTRAINT company_profiles_vat_rate_check
        CHECK (vat_rate IS NULL OR (vat_rate > 0 AND vat_rate <= 100)),
    CONSTRAINT company_profiles_tax_rate_pair_check
        CHECK (tax_regime = 'VAT_REGISTERED' OR vat_rate IS NULL)
);

CREATE TABLE catalog_items
(
    id                         BIGSERIAL PRIMARY KEY,
    user_id                    BIGINT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name                       VARCHAR(255)   NOT NULL,
    unit_price                 NUMERIC(10, 2) NOT NULL,
    unit                       VARCHAR(50)    NOT NULL,
    created_at                 TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    item_type                  VARCHAR(20)    NOT NULL DEFAULT 'OTHER',
    net_procurement_unit_cost  NUMERIC(12, 2),
    estimated_own_labor_unit_cost NUMERIC(12, 2),
    calculation_config         TEXT,
    CONSTRAINT catalog_items_item_type_check
        CHECK (item_type IN ('MATERIAL', 'LABOR', 'COMBINED', 'OTHER')),
    CONSTRAINT catalog_items_non_negative_prices_check
        CHECK (unit_price >= 0
            AND (net_procurement_unit_cost IS NULL OR net_procurement_unit_cost >= 0)
            AND (estimated_own_labor_unit_cost IS NULL OR estimated_own_labor_unit_cost >= 0)),
    CONSTRAINT catalog_items_costs_match_type_check
        CHECK ((item_type IN ('LABOR', 'OTHER') AND net_procurement_unit_cost IS NULL)
            OR item_type IN ('MATERIAL', 'COMBINED')),
    CONSTRAINT catalog_items_labor_cost_match_type_check
        CHECK ((item_type IN ('MATERIAL', 'OTHER') AND estimated_own_labor_unit_cost IS NULL)
            OR item_type IN ('LABOR', 'COMBINED')),
    CONSTRAINT catalog_items_calculation_config_object_check
        CHECK (calculation_config IS NULL OR jsonb_typeof(calculation_config::jsonb) = 'object')
);

CREATE INDEX idx_emails_gmail_connection ON emails (gmail_connection_id);
CREATE INDEX idx_emails_email_received_at ON emails (email_received_at DESC);
CREATE INDEX idx_emails_category ON emails (category);
CREATE INDEX idx_email_drafts_email_id ON email_drafts (email_id);
CREATE INDEX idx_catalog_items_user_id ON catalog_items (user_id);
CREATE INDEX idx_emails_connection_received_id
    ON emails (gmail_connection_id, email_received_at DESC, id DESC);
CREATE INDEX idx_emails_connection_category_received_id
    ON emails (gmail_connection_id, category, email_received_at DESC, id DESC);
CREATE INDEX idx_email_drafts_email_created_id
    ON email_drafts (email_id, created_at DESC, id DESC);
CREATE INDEX idx_emails_subject_trgm ON emails USING GIN (LOWER(subject) gin_trgm_ops);
CREATE INDEX idx_emails_from_address_trgm ON emails USING GIN (LOWER(from_address) gin_trgm_ops);
