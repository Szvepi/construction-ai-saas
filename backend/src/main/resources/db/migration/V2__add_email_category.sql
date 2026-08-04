ALTER TABLE emails
ADD COLUMN category VARCHAR(50) NOT NULL DEFAULT 'OTHER';

CREATE INDEX idx_emails_category ON emails (category);
