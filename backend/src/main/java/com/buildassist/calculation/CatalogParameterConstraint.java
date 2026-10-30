package com.buildassist.calculation;

import java.math.BigDecimal;

public record CatalogParameterConstraint(
        String unit,
        boolean required,
        BigDecimal minimum,
        BigDecimal maximum) {
}
