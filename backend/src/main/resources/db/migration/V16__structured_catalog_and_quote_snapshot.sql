ALTER TABLE catalog_items
    ADD COLUMN item_type VARCHAR(20) NOT NULL DEFAULT 'OTHER',
    ADD COLUMN net_procurement_unit_cost NUMERIC(12, 2),
    ADD COLUMN estimated_own_labor_unit_cost NUMERIC(12, 2),
    ADD COLUMN calculation_config TEXT;

ALTER TABLE catalog_items
    ADD CONSTRAINT catalog_items_item_type_check
        CHECK (item_type IN ('MATERIAL', 'LABOR', 'COMBINED', 'OTHER')),
    ADD CONSTRAINT catalog_items_non_negative_prices_check
        CHECK (unit_price >= 0
            AND (net_procurement_unit_cost IS NULL OR net_procurement_unit_cost >= 0)
            AND (estimated_own_labor_unit_cost IS NULL OR estimated_own_labor_unit_cost >= 0)),
    ADD CONSTRAINT catalog_items_costs_match_type_check
        CHECK (
            (item_type IN ('LABOR', 'OTHER') AND net_procurement_unit_cost IS NULL)
            OR item_type IN ('MATERIAL', 'COMBINED')
        ),
    ADD CONSTRAINT catalog_items_labor_cost_match_type_check
        CHECK (
            (item_type IN ('MATERIAL', 'OTHER') AND estimated_own_labor_unit_cost IS NULL)
            OR item_type IN ('LABOR', 'COMBINED')
        ),
    ADD CONSTRAINT catalog_items_calculation_config_object_check
        CHECK (calculation_config IS NULL OR jsonb_typeof(calculation_config::jsonb) = 'object');

ALTER TABLE email_drafts
    ADD COLUMN quote_snapshot TEXT,
    ADD CONSTRAINT email_drafts_quote_snapshot_object_check
        CHECK (quote_snapshot IS NULL OR jsonb_typeof(quote_snapshot::jsonb) = 'object');
