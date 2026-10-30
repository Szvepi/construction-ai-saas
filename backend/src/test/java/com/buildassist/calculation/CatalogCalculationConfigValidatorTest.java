package com.buildassist.calculation;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogCalculationConfigValidatorTest {

    private final CatalogCalculationConfigValidator validator = new CatalogCalculationConfigValidator();

    @Test
    void validatesContractorDefaultsConstraintsAndAllowance() {
        CatalogCalculationConfig valid = new CatalogCalculationConfig(
                Set.of(CalculationOperation.VOLUME, CalculationOperation.PERCENT_ADJUSTMENT),
                Map.of("thickness", new CatalogParameterDefault(
                        new BigDecimal("15"), "cm", InputSource.CONTRACTOR_PROFILE)),
                Map.of("thickness", new CatalogParameterConstraint(
                        "cm", true, new BigDecimal("1"), new BigDecimal("30"))),
                new BigDecimal("10"),
                new QuantityRoundingPolicy(2, java.math.RoundingMode.HALF_UP));

        assertTrue(validator.validate(valid).isEmpty());
    }

    @Test
    void rejectsInvalidUnitsAssumptionSourcesAndParameterBounds() {
        CatalogCalculationConfig invalid = new CatalogCalculationConfig(
                Set.of(CalculationOperation.DIRECT),
                Map.of("depth", new CatalogParameterDefault(
                        new BigDecimal("40"), "yards", InputSource.AI_ASSUMPTION)),
                Map.of("depth", new CatalogParameterConstraint(
                        "m", true, new BigDecimal("0.1"), new BigDecimal("30"))),
                new BigDecimal("101"),
                new QuantityRoundingPolicy(7, java.math.RoundingMode.HALF_UP));

        var errors = validator.validate(invalid);

        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(error -> error.contains("unknown unit")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("catalog or contractor profile")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("units must match")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("exceeds its maximum")));
        assertEquals(6, errors.size());
    }

    @Test
    void rejectsDefaultValuesOutsideCalculatorNumericLimits() {
        CatalogCalculationConfig config = new CatalogCalculationConfig(
                Set.of(CalculationOperation.DIRECT),
                Map.of("amount", new CatalogParameterDefault(
                        new BigDecimal("1000000000001"), "scalar", InputSource.CATALOG_DEFAULT)),
                Map.of(), null, null);

        assertTrue(validator.validate(config).stream()
                .anyMatch(error -> error.contains("supported numeric range")));
    }
}
