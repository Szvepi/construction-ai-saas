package com.buildassist.calculation;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;

public record CatalogCalculationConfig(
        Set<CalculationOperation> allowedCalculations,
        Map<String, CatalogParameterDefault> defaultParameters,
        Map<String, CatalogParameterConstraint> parameterConstraints,
        BigDecimal materialAllowancePercent,
        QuantityRoundingPolicy roundingPolicy) {

    public CatalogCalculationConfig {
        allowedCalculations = allowedCalculations == null ? Set.of()
                : Collections.unmodifiableSet(new TreeSet<>(allowedCalculations));
        defaultParameters = defaultParameters == null ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(defaultParameters));
        parameterConstraints = parameterConstraints == null ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(parameterConstraints));
    }

    public static CatalogCalculationConfig empty() {
        return new CatalogCalculationConfig(Set.of(), Map.of(), Map.of(), null, null);
    }
}
