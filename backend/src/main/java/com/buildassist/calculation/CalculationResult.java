package com.buildassist.calculation;

import java.util.List;

public record CalculationResult(
        CalculationStatus status,
        Quantity quantity,
        List<CalculationIssue> issues,
        List<CalculationTraceEntry> trace) {

    public CalculationResult {
        issues = List.copyOf(issues);
        trace = List.copyOf(trace);
        if (status != CalculationStatus.VALID && quantity != null) {
            throw new IllegalArgumentException("Only a valid calculation may expose a final quantity.");
        }
    }
}
