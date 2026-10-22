CREATE TABLE company_profiles
(
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    company_name VARCHAR(255) NOT NULL,
    legal_form   VARCHAR(255) NOT NULL,
    tax_number   VARCHAR(255) NOT NULL,
    eu_tax_number VARCHAR(255),
    country      VARCHAR(100) NOT NULL DEFAULT 'Magyarország',
    postal_code  VARCHAR(255) NOT NULL,
    city         VARCHAR(255) NOT NULL,
    street_name  VARCHAR(255) NOT NULL,
    street_type  VARCHAR(100) NOT NULL,
    house_number VARCHAR(50)  NOT NULL,
    building     VARCHAR(100),
    staircase    VARCHAR(100),
    floor_number VARCHAR(50),
    door         VARCHAR(50)
);
