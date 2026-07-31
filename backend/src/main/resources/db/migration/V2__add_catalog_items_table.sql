CREATE TABLE catalog_items
(
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name                 VARCHAR(255)   NOT NULL,
    unit_price           NUMERIC(10, 2) NOT NULL,
    unit                 VARCHAR(50)    NOT NULL,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    calculation_strategy VARCHAR(50)    NOT NULL
);

CREATE INDEX idx_catalog_items_user_id ON catalog_items (user_id);
