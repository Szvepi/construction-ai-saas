package com.buildassist.calculation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class CalculationPlanValidator {

    public static final int MAX_INPUTS = 64;
    public static final int MAX_STEPS = 32;
    public static final int MAX_OPERANDS = 8;
    public static final int MAX_ID_LENGTH = 80;
    public static final int MAX_SCALE = 12;

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}");

    public List<CalculationIssue> validate(CalculationPlan plan) {
        List<CalculationIssue> issues = new ArrayList<>();
        if (plan == null) {
            return List.of(new CalculationIssue("PLAN_REQUIRED", "A calculation plan is required.", null));
        }
        if (plan.inputs() == null) {
            issues.add(new CalculationIssue("INPUTS_REQUIRED", "Calculation inputs are required.", null));
            return List.copyOf(issues);
        }
        if (plan.steps() == null) {
            issues.add(new CalculationIssue("STEPS_REQUIRED", "Calculation steps are required.", null));
            return List.copyOf(issues);
        }
        if (plan.inputs().size() > MAX_INPUTS) {
            issues.add(new CalculationIssue("TOO_MANY_INPUTS", "The plan exceeds the input limit.", null));
        }
        if (plan.steps().size() > MAX_STEPS) {
            issues.add(new CalculationIssue("TOO_MANY_STEPS", "The plan exceeds the step limit.", null));
        }

        Set<String> availableReferences = new HashSet<>();
        for (CalculationInput input : plan.inputs()) {
            if (input == null) {
                issues.add(new CalculationIssue("INVALID_INPUT", "An input entry is null.", null));
                continue;
            }
            validateId(input.id(), "input", issues);
            if (input.id() != null && !availableReferences.add(input.id())) {
                issues.add(new CalculationIssue("DUPLICATE_REFERENCE", "Input identifiers must be unique.", input.id()));
            }
            if (input.unit() == null || input.unit().isBlank()) {
                issues.add(new CalculationIssue("UNIT_REQUIRED", "An input unit is required.", input.id()));
            } else {
                try {
                    QuantityUnit.fromCode(input.unit());
                } catch (IllegalArgumentException exception) {
                    issues.add(new CalculationIssue("UNKNOWN_UNIT", exception.getMessage(), input.id()));
                }
            }
            if (input.value() != null) {
                try {
                    new Quantity(input.value(), QuantityUnit.fromCode(input.unit()));
                    if (input.value().signum() < 0) {
                        issues.add(new CalculationIssue("NEGATIVE_INPUT", "Negative input quantities are not supported.", input.id()));
                    }
                } catch (IllegalArgumentException exception) {
                    if (!"UNKNOWN_UNIT".equals(lastIssueCode(issues, input.id()))) {
                        issues.add(new CalculationIssue("INVALID_NUMBER", exception.getMessage(), input.id()));
                    }
                }
            }
            if (input.source() == null) {
                issues.add(new CalculationIssue("SOURCE_REQUIRED", "An input source is required.", input.id()));
            }
        }

        for (CalculationStep step : plan.steps()) {
            if (step == null) {
                issues.add(new CalculationIssue("INVALID_STEP", "A calculation step is null.", null));
                continue;
            }
            validateId(step.id(), "step", issues);
            if (step.id() != null && !availableReferences.add(step.id())) {
                issues.add(new CalculationIssue("DUPLICATE_REFERENCE", "Input and step identifiers must be unique.", step.id()));
            }
            if (step.operation() == null) {
                issues.add(new CalculationIssue("OPERATION_REQUIRED", "A step operation is required.", step.id()));
            }
            if (step.operands() == null || step.operands().isEmpty() || step.operands().size() > MAX_OPERANDS) {
                issues.add(new CalculationIssue("INVALID_OPERANDS", "A step must have between 1 and 8 operands.", step.id()));
            } else {
                for (String operand : step.operands()) {
                    if (operand == null || !SAFE_ID.matcher(operand).matches()) {
                        issues.add(new CalculationIssue("INVALID_REFERENCE", "An operand reference is invalid.", step.id()));
                    } else if (!availableReferences.contains(operand)) {
                        issues.add(new CalculationIssue(
                                "FORWARD_OR_UNKNOWN_REFERENCE",
                                "A step may only reference inputs or earlier steps.",
                                operand));
                    }
                }
            }
            if (step.outputUnit() != null) {
                try {
                    QuantityUnit.fromCode(step.outputUnit());
                } catch (IllegalArgumentException exception) {
                    issues.add(new CalculationIssue("UNKNOWN_OUTPUT_UNIT", exception.getMessage(), step.id()));
                }
            }
            if (step.scale() != null && (step.scale() < 0 || step.scale() > MAX_SCALE)) {
                issues.add(new CalculationIssue("INVALID_SCALE", "Rounding scale must be between 0 and 12.", step.id()));
            }
            if (step.operation() == CalculationOperation.ROUND
                    && step.roundingMode() == null) {
                issues.add(new CalculationIssue("ROUNDING_MODE_REQUIRED", "ROUND requires an explicit rounding mode.", step.id()));
            }
            if (step.operation() != CalculationOperation.ROUND && step.roundingMode() != null) {
                issues.add(new CalculationIssue("UNEXPECTED_ROUNDING_MODE", "A rounding mode is only valid for ROUND.", step.id()));
            }
            if (step.operation() != CalculationOperation.ROUND && step.scale() != null) {
                issues.add(new CalculationIssue("UNEXPECTED_SCALE", "A scale is only valid for ROUND.", step.id()));
            }
        }

        if (plan.outputReference() == null || !availableReferences.contains(plan.outputReference())) {
            issues.add(new CalculationIssue("INVALID_OUTPUT_REFERENCE", "The output must reference an input or step.", plan.outputReference()));
        }
        return List.copyOf(issues);
    }

    private void validateId(String id, String kind, List<CalculationIssue> issues) {
        if (id == null || !SAFE_ID.matcher(id).matches() || id.length() > MAX_ID_LENGTH) {
            issues.add(new CalculationIssue("INVALID_ID", "A " + kind + " identifier is invalid.", id));
        }
    }

    private String lastIssueCode(List<CalculationIssue> issues, String reference) {
        for (int index = issues.size() - 1; index >= 0; index--) {
            CalculationIssue issue = issues.get(index);
            if (java.util.Objects.equals(issue.reference(), reference)) {
                return issue.code();
            }
        }
        return null;
    }
}
