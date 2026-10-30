package com.buildassist.calculation;

import java.math.BigDecimal;

public record CalculationInput(
        String id,
        BigDecimal value,
        String unit,
        InputSource source,
        boolean approved,
        String evidence) {

    public CalculationInput(
            String id, BigDecimal value, String unit, InputSource source, boolean approved) {
        this(id, value, unit, source, approved, null);
    }
}
