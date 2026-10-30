ALTER TABLE company_profiles
    ADD COLUMN tax_regime VARCHAR(32) NOT NULL DEFAULT 'AAM',
    ADD COLUMN vat_rate NUMERIC(5, 2);

UPDATE company_profiles
SET tax_regime = CASE WHEN vat_registered THEN 'VAT_REGISTERED' ELSE 'AAM' END;

ALTER TABLE company_profiles
    ADD CONSTRAINT company_profiles_tax_regime_check
        CHECK (tax_regime IN ('AAM', 'VAT_REGISTERED', 'REVERSE_CHARGE_UNSUPPORTED')),
    ADD CONSTRAINT company_profiles_vat_rate_check
        CHECK (vat_rate IS NULL OR (vat_rate > 0 AND vat_rate <= 100)),
    ADD CONSTRAINT company_profiles_tax_rate_pair_check
        CHECK ((tax_regime = 'VAT_REGISTERED')
            OR vat_rate IS NULL);
