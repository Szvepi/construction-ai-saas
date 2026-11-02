\set ON_ERROR_STOP on

BEGIN;

SELECT id AS seed_user_id
FROM users
WHERE email = :'seed_email'
\gset

INSERT INTO catalog_items (
    user_id,
    name,
    unit_price,
    unit,
    item_type,
    net_procurement_unit_cost,
    estimated_own_labor_unit_cost,
    calculation_config
)
SELECT
    :seed_user_id,
    seed.name,
    seed.unit_price,
    seed.unit,
    seed.item_type,
    seed.procurement_cost,
    seed.labor_cost,
    seed.calculation_config
FROM (VALUES
    ('Földkiemelés', 1200.00::numeric, 'm3', 'LABOR', NULL::numeric, 1200.00::numeric, NULL::text),
    ('Zúzottkő alapréteg', 8500.00::numeric, 'm3', 'MATERIAL', 5000.00::numeric, NULL::numeric,
        '{"allowedCalculations":["DIRECT","VOLUME"],"defaultParameters":{"thickness":{"value":15,"unit":"cm","source":"CONTRACTOR_PROFILE"}},"parameterConstraints":{"thickness":{"unit":"cm","required":true,"minimum":1,"maximum":100}}}'),
    ('Térkőanyag', 4800.00::numeric, 'm2', 'MATERIAL', 3500.00::numeric, NULL::numeric,
        '{"allowedCalculations":["DIRECT","PERCENT_ADJUSTMENT"],"materialAllowancePercent":10}'),
    ('Szegélyezés', 2100.00::numeric, 'm', 'MATERIAL', 1200.00::numeric, NULL::numeric, NULL::text),
    ('Térkőlerakási munkadíj', 1600.00::numeric, 'm2', 'LABOR', NULL::numeric, 900.00::numeric, NULL::text),
    ('Falfestés', 900.00::numeric, 'm2', 'LABOR', NULL::numeric, 550.00::numeric, NULL::text),
    ('Mennyezetfestés', 1000.00::numeric, 'm2', 'LABOR', NULL::numeric, 650.00::numeric, NULL::text),
    ('Festékanyag', 2400.00::numeric, 'l', 'MATERIAL', 1500.00::numeric, NULL::numeric,
        '{"allowedCalculations":["DIRECT","DIVIDE","MULTIPLY","ROUND_UP_TO_MULTIPLE"],"defaultParameters":{"coverage":{"value":10,"unit":"m2/l","source":"CONTRACTOR_PROFILE"},"packageSize":{"value":2.5,"unit":"l","source":"CATALOG_DEFAULT"}},"parameterConstraints":{"coverage":{"unit":"m2/l","required":true,"minimum":0.1,"maximum":1000},"packageSize":{"unit":"l","required":true,"minimum":0.1,"maximum":100}}}'),
    ('Konnektorszerelés', 950.00::numeric, 'db', 'LABOR', NULL::numeric, 600.00::numeric, NULL::text),
    ('Kapcsolószerelés', 900.00::numeric, 'db', 'LABOR', NULL::numeric, 550.00::numeric, NULL::text),
    ('Lámpakiállás', 1250.00::numeric, 'db', 'LABOR', NULL::numeric, 800.00::numeric, NULL::text),
    ('Konnektor anyag', 680.00::numeric, 'db', 'MATERIAL', 420.00::numeric, NULL::numeric, NULL::text),
    ('Kapcsoló anyag', 720.00::numeric, 'db', 'MATERIAL', 450.00::numeric, NULL::numeric, NULL::text),
    ('Lámpakiállás anyag', 1100.00::numeric, 'db', 'MATERIAL', 700.00::numeric, NULL::numeric, NULL::text)
) AS seed(name, unit_price, unit, item_type, procurement_cost, labor_cost, calculation_config)
WHERE NOT EXISTS (
    SELECT 1
    FROM catalog_items existing
    WHERE existing.user_id = :seed_user_id
      AND existing.name = seed.name
);

COMMIT;
