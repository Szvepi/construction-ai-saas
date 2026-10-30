package com.buildassist.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DeterministicQuantityCalculator {

    private static final int DIVISION_SCALE = 12;

    private final CalculationPlanValidator validator;

    public DeterministicQuantityCalculator() {
        this(new CalculationPlanValidator());
    }

    public DeterministicQuantityCalculator(CalculationPlanValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public CalculationResult calculate(CalculationPlan plan) {
        List<CalculationIssue> validationIssues = validator.validate(plan);
        if (!validationIssues.isEmpty()) {
            return invalid(validationIssues);
        }

        List<CalculationIssue> issues = new ArrayList<>();
        Map<String, Quantity> values = new HashMap<>();
        boolean needsInput = false;
        boolean needsReview = !plan.reviewReasons().isEmpty();

        for (CalculationInput input : plan.inputs()) {
            if (input.value() == null) {
                needsInput = true;
                issues.add(new CalculationIssue("NEEDS_INPUT", "A required input value is missing.", input.id()));
                continue;
            }
            if (input.source() == InputSource.AI_ASSUMPTION && !input.approved()) {
                needsReview = true;
                issues.add(new CalculationIssue(
                        "ASSUMPTION_NOT_APPROVED",
                        "An AI assumption must be approved before it can be used as a final quantity.",
                        input.id()));
            }
            try {
                values.put(input.id(), new Quantity(input.value(), QuantityUnit.fromCode(input.unit())));
            } catch (IllegalArgumentException exception) {
                return invalid(List.of(new CalculationIssue("INVALID_INPUT", exception.getMessage(), input.id())));
            }
        }

        if (!plan.reviewReasons().isEmpty()) {
            plan.reviewReasons().forEach(reason -> issues.add(
                    new CalculationIssue("NEEDS_REVIEW", reason, null)));
        }
        if (needsInput || needsReview) {
            return new CalculationResult(
                    needsInput ? CalculationStatus.NEEDS_INPUT : CalculationStatus.NEEDS_REVIEW,
                    null, issues, List.of());
        }

        List<CalculationTraceEntry> trace = new ArrayList<>();
        for (CalculationStep step : plan.steps()) {
            List<Quantity> operands = step.operands().stream().map(values::get).toList();
            if (operands.stream().anyMatch(Objects::isNull)) {
                needsInput = true;
                issues.add(new CalculationIssue(
                        "NEEDS_INPUT",
                        "A calculation step depends on an input whose value is missing.",
                        step.id()));
                continue;
            }
            try {
                Quantity result = evaluate(step, operands);
                if (step.outputUnit() != null) {
                    result = result.convertTo(QuantityUnit.fromCode(step.outputUnit()));
                }
                ensureSupported(result.value());
                values.put(step.id(), result);
                trace.add(new CalculationTraceEntry(step.id(), step.operation(), step.operands(), result));
            } catch (ArithmeticException | IllegalArgumentException exception) {
                return invalid(List.of(new CalculationIssue(
                        "INVALID_CALCULATION",
                        safeMessage(exception),
                        step.id())));
            }
        }
        if (needsInput) {
            return new CalculationResult(CalculationStatus.NEEDS_INPUT, null, issues, trace);
        }
        Quantity result = values.get(plan.outputReference());
        if (result == null) {
            return invalid(List.of(new CalculationIssue(
                    "INVALID_OUTPUT",
                    "The output quantity could not be calculated.",
                    plan.outputReference())));
        }
        return new CalculationResult(CalculationStatus.VALID, result, issues, trace);
    }

    private Quantity evaluate(CalculationStep step, List<Quantity> values) {
        return switch (step.operation()) {
            case DIRECT -> {
                requireArity(step, 1, 1);
                yield values.get(0);
            }
            case ADD -> add(values, step, false);
            case PERIMETER -> add(values, step, true);
            case SUBTRACT -> subtract(step, values);
            case MULTIPLY -> multiply(values, step);
            case DIVIDE -> divide(step, values);
            case AREA -> area(step, values);
            case VOLUME -> volume(step, values);
            case PERCENT_ADJUSTMENT -> percentAdjustment(step, values);
            case COUNT -> count(step, values);
            case ROUND -> round(step, values);
            case ROUND_UP_TO_MULTIPLE -> roundUpToMultiple(step, values);
        };
    }

    private Quantity add(List<Quantity> values, CalculationStep step, boolean perimeter) {
        requireArity(step, perimeter ? 2 : 2, CalculationPlanValidator.MAX_OPERANDS);
        QuantityUnit firstUnit = values.get(0).unit();
        if (perimeter && firstUnit.dimension().length() != 1) {
            throw new IllegalArgumentException("PERIMETER accepts only length quantities.");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Quantity value : values) {
            requireSameKind(firstUnit, value.unit());
            if (perimeter && value.unit().dimension().length() != 1) {
                throw new IllegalArgumentException("PERIMETER accepts only length quantities.");
            }
            sum = sum.add(value.value().multiply(value.unit().toBaseFactor()));
        }
        if (sum.signum() < 0) {
            throw new IllegalArgumentException("A calculated quantity cannot be negative.");
        }
        return Quantity.canonical(sum, firstUnit.dimension(), firstUnit.semantic());
    }

    private Quantity subtract(CalculationStep step, List<Quantity> values) {
        requireArity(step, 2, 2);
        Quantity left = values.get(0);
        Quantity right = values.get(1);
        requireSameKind(left.unit(), right.unit());
        BigDecimal difference = left.value().multiply(left.unit().toBaseFactor())
                .subtract(right.value().multiply(right.unit().toBaseFactor()));
        if (difference.signum() < 0) {
            throw new IllegalArgumentException("A calculated quantity cannot be negative.");
        }
        return Quantity.canonical(difference, left.unit().dimension(), left.unit().semantic());
    }

    private Quantity multiply(List<Quantity> values, CalculationStep step) {
        requireArity(step, 2, CalculationPlanValidator.MAX_OPERANDS);
        BigDecimal product = BigDecimal.ONE;
        UnitDimension dimension = new UnitDimension(0, 0, 0, 0);
        for (Quantity quantity : values) {
            product = product.multiply(quantity.value().multiply(quantity.unit().toBaseFactor()));
            UnitDimension factorDimension = quantity.unit().semantic() == QuantityUnit.Semantic.COUNT
                    ? new UnitDimension(0, 0, 0, 0)
                    : quantity.unit().dimension();
            dimension = dimension.multiply(factorDimension);
            ensureSupported(product);
        }
        return Quantity.canonical(product, dimension, QuantityUnit.Semantic.PHYSICAL);
    }

    private Quantity divide(CalculationStep step, List<Quantity> values) {
        requireArity(step, 2, 2);
        Quantity numerator = values.get(0);
        Quantity denominator = values.get(1);
        BigDecimal denominatorBase = denominator.value().multiply(denominator.unit().toBaseFactor());
        if (denominatorBase.signum() == 0) {
            throw new ArithmeticException("Division by zero is not allowed.");
        }
        UnitDimension numeratorDimension = numerator.unit().semantic() == QuantityUnit.Semantic.COUNT
                ? new UnitDimension(0, 0, 0, 0) : numerator.unit().dimension();
        UnitDimension denominatorDimension = denominator.unit().semantic() == QuantityUnit.Semantic.COUNT
                ? new UnitDimension(0, 0, 0, 0) : denominator.unit().dimension();
        BigDecimal result = numerator.value().multiply(numerator.unit().toBaseFactor())
                .divide(denominatorBase, DIVISION_SCALE, RoundingMode.HALF_UP);
        if (result.signum() < 0) {
            throw new IllegalArgumentException("A calculated quantity cannot be negative.");
        }
        UnitDimension dimension = numeratorDimension.divide(denominatorDimension);
        QuantityUnit.Semantic semantic = dimension.equals(new UnitDimension(0, 0, 0, 0))
                ? QuantityUnit.Semantic.SCALAR : QuantityUnit.Semantic.PHYSICAL;
        return Quantity.canonical(result, dimension, semantic);
    }

    private Quantity area(CalculationStep step, List<Quantity> values) {
        requireArity(step, 2, 2);
        requireLengths(values);
        BigDecimal area = values.get(0).value().multiply(values.get(0).unit().toBaseFactor())
                .multiply(values.get(1).value().multiply(values.get(1).unit().toBaseFactor()));
        ensureSupported(area);
        return Quantity.canonical(area, new UnitDimension(2, 0, 0, 0), QuantityUnit.Semantic.PHYSICAL);
    }

    private Quantity volume(CalculationStep step, List<Quantity> values) {
        requireArity(step, 2, 3);
        BigDecimal volume;
        if (values.size() == 2
                && values.get(0).unit().dimension().equals(new UnitDimension(2, 0, 0, 0))
                && values.get(1).unit().dimension().equals(new UnitDimension(1, 0, 0, 0))) {
            volume = values.get(0).value().multiply(values.get(0).unit().toBaseFactor())
                    .multiply(values.get(1).value().multiply(values.get(1).unit().toBaseFactor()));
        } else if (values.size() == 3) {
            requireLengths(values);
            volume = values.stream()
                    .map(value -> value.value().multiply(value.unit().toBaseFactor()))
                    .reduce(BigDecimal.ONE, BigDecimal::multiply);
        } else {
            throw new IllegalArgumentException("VOLUME requires area and length, or three lengths.");
        }
        ensureSupported(volume);
        return Quantity.canonical(volume, new UnitDimension(3, 0, 0, 0), QuantityUnit.Semantic.PHYSICAL);
    }

    private Quantity percentAdjustment(CalculationStep step, List<Quantity> values) {
        requireArity(step, 2, 2);
        Quantity base = values.get(0);
        Quantity percent = values.get(1);
        if (percent.unit().semantic() != QuantityUnit.Semantic.PERCENT) {
            throw new IllegalArgumentException("PERCENT_ADJUSTMENT requires a percentage as its second operand.");
        }
        BigDecimal adjusted = base.value().multiply(
                BigDecimal.ONE.add(percent.value().multiply(percent.unit().toBaseFactor())));
        ensureSupported(adjusted);
        return new Quantity(adjusted.stripTrailingZeros(), base.unit());
    }

    private Quantity count(CalculationStep step, List<Quantity> values) {
        requireArity(step, 1, 1);
        Quantity value = values.get(0);
        if (value.unit().semantic() == QuantityUnit.Semantic.COUNT) {
            return value;
        }
        if (value.unit().semantic() != QuantityUnit.Semantic.SCALAR
                || value.value().stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("COUNT requires a non-negative integer scalar or count.");
        }
        return new Quantity(value.value(), QuantityUnit.EACH);
    }

    private Quantity round(CalculationStep step, List<Quantity> values) {
        requireArity(step, 1, 1);
        int scale = step.scale() == null ? 2 : step.scale();
        RoundingMode mode = Objects.requireNonNull(step.roundingMode());
        return new Quantity(values.get(0).value().setScale(scale, mode).stripTrailingZeros(), values.get(0).unit());
    }

    private Quantity roundUpToMultiple(CalculationStep step, List<Quantity> values) {
        requireArity(step, 2, 2);
        Quantity value = values.get(0);
        Quantity multiple = values.get(1);
        requireSameKind(value.unit(), multiple.unit());
        if (multiple.value().signum() <= 0) {
            throw new IllegalArgumentException("The rounding multiple must be greater than zero.");
        }
        BigDecimal quotient = value.value().multiply(value.unit().toBaseFactor())
                .divide(multiple.value().multiply(multiple.unit().toBaseFactor()), 0, RoundingMode.CEILING);
        BigDecimal result = quotient.multiply(multiple.value().multiply(multiple.unit().toBaseFactor()));
        ensureSupported(result);
        return Quantity.canonical(result, value.unit().dimension(), value.unit().semantic());
    }

    private void requireLengths(List<Quantity> values) {
        if (values.stream().anyMatch(value ->
                value.unit().semantic() != QuantityUnit.Semantic.PHYSICAL
                        || !value.unit().dimension().equals(new UnitDimension(1, 0, 0, 0)))) {
            throw new IllegalArgumentException("The operation requires length quantities.");
        }
    }

    private void requireSameKind(QuantityUnit first, QuantityUnit second) {
        if (!first.dimension().equals(second.dimension()) || first.semantic() != second.semantic()) {
            throw new IllegalArgumentException("The operation requires compatible quantity units.");
        }
    }

    private void requireArity(CalculationStep step, int minimum, int maximum) {
        int actual = step.operands().size();
        if (actual < minimum || actual > maximum) {
            throw new IllegalArgumentException(
                    step.operation() + " requires between " + minimum + " and " + maximum + " operands.");
        }
    }

    private void ensureSupported(BigDecimal value) {
        if (value.precision() > Quantity.MAX_PRECISION
                || Math.max(0, value.scale()) > Quantity.MAX_SCALE
                || value.abs().compareTo(Quantity.MAX_ABSOLUTE_VALUE) > 0) {
            throw new IllegalArgumentException("A calculated quantity exceeds the supported numeric range.");
        }
    }

    private CalculationResult invalid(List<CalculationIssue> issues) {
        return new CalculationResult(CalculationStatus.INVALID, null, issues, List.of());
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? "The calculation is invalid." : exception.getMessage();
    }
}
