package com.buildassist.calculation;

import java.util.List;

public record CalculationTraceEntry(
        String stepId,
        CalculationOperation operation,
        List<String> operands,
        Quantity result) {

    public CalculationTraceEntry {
        operands = List.copyOf(operands);
    }
}
