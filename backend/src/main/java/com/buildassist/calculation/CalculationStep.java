package com.buildassist.calculation;

import java.math.RoundingMode;
import java.util.List;

public record CalculationStep(
        String id,
        CalculationOperation operation,
        List<String> operands,
        String outputUnit,
        Integer scale,
        RoundingMode roundingMode) {

    public CalculationStep {
        operands = operands == null ? null : List.copyOf(operands);
    }

    public CalculationStep(String id, CalculationOperation operation, List<String> operands) {
        this(id, operation, operands, null, null, null);
    }
}
