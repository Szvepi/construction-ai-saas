package com.buildassist.calculation;

import java.util.List;

public record CalculationPlan(
        List<CalculationInput> inputs,
        List<CalculationStep> steps,
        String outputReference,
        List<String> reviewReasons) {

    public CalculationPlan {
        inputs = inputs == null ? null : List.copyOf(inputs);
        steps = steps == null ? null : List.copyOf(steps);
        reviewReasons = reviewReasons == null ? List.of() : List.copyOf(reviewReasons);
    }
}
