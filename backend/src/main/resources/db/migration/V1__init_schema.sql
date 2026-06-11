CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE gmail_connections (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    gmail_address           VARCHAR(255) NOT NULL,
    access_token_encrypted  TEXT NOT NULL,
    refresh_token_encrypted TEXT NOT NULL,
    token_expires_at        TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id)
);

CREATE TABLE emails (
    id                  BIGSERIAL PRIMARY KEY,
    gmail_connection_id BIGINT NOT NULL REFERENCES gmail_connections(id) ON DELETE CASCADE,
    gmail_message_id    VARCHAR(255) NOT NULL,
    subject             VARCHAR(500),
    from_address        VARCHAR(255),
    body_text           TEXT,
    received_at         TIMESTAMPTZ,
    is_replied          BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (gmail_connection_id, gmail_message_id)
);

CREATE TABLE email_drafts (
    id          BIGSERIAL PRIMARY KEY,
    email_id    BIGINT NOT NULL REFERENCES emails(id) ON DELETE CASCADE,
    draft_body  TEXT NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    sent_at     TIMESTAMPTZ,
    CONSTRAINT email_drafts_status_check CHECK (status IN ('DRAFT', 'SENT'))
);

CREATE INDEX idx_emails_gmail_connection ON emails(gmail_connection_id);
CREATE INDEX idx_emails_received_at ON emails(received_at DESC);
CREATE INDEX idx_email_drafts_email_id ON email_drafts(email_id);
