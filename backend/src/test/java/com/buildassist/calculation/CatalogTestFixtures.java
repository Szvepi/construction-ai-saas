package com.buildassist.calculation;

import com.buildassist.dto.CatalogDtos.CreateCatalogItemRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CatalogTestFixtures {

    private CatalogTestFixtures() {
    }

    public static List<CreateCatalogItemRequest> allTrades() {
        List<CreateCatalogItemRequest> items = new ArrayList<>();
        items.add(item("Térkő földkiemelés", "m2", CatalogItemType.LABOR, null, "1200", null));
        items.add(item("Zúzottkő alapréteg", "m3", CatalogItemType.MATERIAL, "8500", null,
                new CatalogCalculationConfig(
                        Set.of(CalculationOperation.VOLUME, CalculationOperation.DIRECT),
                        Map.of("thickness", new CatalogParameterDefault(
                                new BigDecimal("15"), "cm", InputSource.CONTRACTOR_PROFILE)),
                        Map.of("thickness", new CatalogParameterConstraint(
                                "cm", true, new BigDecimal("1"), new BigDecimal("100"))),
                        null, null)));
        items.add(item("Szegélykő", "m", CatalogItemType.MATERIAL, "2100", null, null));
        items.add(item("Térkőanyag", "m2", CatalogItemType.MATERIAL, "4800", null,
                new CatalogCalculationConfig(
                        Set.of(CalculationOperation.PERCENT_ADJUSTMENT, CalculationOperation.DIRECT),
                        Map.of(), Map.of(), new BigDecimal("10"), null)));
        items.add(item("Térkőlerakási munkadíj", "m2", CatalogItemType.LABOR, null, "1600", null));

        items.add(item("Falfestési munkadíj", "m2", CatalogItemType.LABOR, null, "900", null));
        items.add(item("Mennyezetfestési munkadíj", "m2", CatalogItemType.LABOR, null, "1000", null));
        items.add(item("Festékanyag", "l", CatalogItemType.MATERIAL, "2400", null,
                new CatalogCalculationConfig(
                        Set.of(CalculationOperation.DIRECT, CalculationOperation.MULTIPLY, CalculationOperation.DIVIDE,
                                CalculationOperation.ROUND_UP_TO_MULTIPLE),
                        Map.of(
                                "coverage", new CatalogParameterDefault(
                                        new BigDecimal("10"), "m2/l", InputSource.CONTRACTOR_PROFILE),
                                "packageSize", new CatalogParameterDefault(
                                        new BigDecimal("2.5"), "l", InputSource.CATALOG_DEFAULT)),
                        Map.of(
                                "coverage", new CatalogParameterConstraint(
                                        "m2/l", true, new BigDecimal("0.1"), new BigDecimal("1000")),
                                "packageSize", new CatalogParameterConstraint(
                                        "l", true, new BigDecimal("0.1"), new BigDecimal("100"))),
                        null, null)));
        items.add(item("Gipszkarton válaszfal", "m2", CatalogItemType.COMBINED,
                "5200", "1800", null));

        items.add(item("Konnektorszerelés", "db", CatalogItemType.LABOR, null, "950", null));
        items.add(item("Kapcsolószerelés", "db", CatalogItemType.LABOR, null, "900", null));
        items.add(item("Lámpakiállás", "db", CatalogItemType.LABOR, null, "1250", null));
        items.add(item("Konnektor anyag", "db", CatalogItemType.MATERIAL, "680", null, null));
        items.add(item("Kapcsoló anyag", "db", CatalogItemType.MATERIAL, "720", null, null));
        items.add(item("Lámpakiállás anyag", "db", CatalogItemType.MATERIAL, "1100", null, null));
        items.add(item("Kiszállási díj", "alkalom", CatalogItemType.OTHER, null, null, null));
        return List.copyOf(items);
    }

    private static CreateCatalogItemRequest item(
            String name,
            String unit,
            CatalogItemType type,
            String procurementCost,
            String laborCost,
            CatalogCalculationConfig config) {
        return new CreateCatalogItemRequest(
                name,
                new BigDecimal("1234.50"),
                unit,
                type,
                decimal(procurementCost),
                decimal(laborCost),
                config);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
