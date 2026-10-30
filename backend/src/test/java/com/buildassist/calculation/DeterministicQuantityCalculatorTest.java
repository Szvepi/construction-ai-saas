package com.buildassist.calculation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicQuantityCalculatorTest {

    private final DeterministicQuantityCalculator calculator = new DeterministicQuantityCalculator();

    @Test
    void calculatesPavingWorkMaterialAllowanceVolumeAndBorderSeparately() {
        CalculationResult labor = calculate(
                input("area", "45", "m2"),
                List.of(new CalculationStep("labor", CalculationOperation.DIRECT,
                        List.of("area"), "m2", null, null)),
                "labor");
        CalculationResult material = calculate(
                input("area", "45", "m2"),
                List.of(step("adjusted", CalculationOperation.PERCENT_ADJUSTMENT, "area", "waste")),
                List.of(input("waste", "10", "%")),
                "adjusted");
        CalculationResult volume = calculate(
                input("area", "45", "m2"),
                List.of(step("volume", CalculationOperation.VOLUME, "area", "thickness")),
                List.of(input("thickness", "15", "cm")),
                "volume");
        CalculationResult border = calculate(
                input("side", "15", "m"),
                List.of(step("border", CalculationOperation.MULTIPLY, "side", "sides")),
                List.of(input("sides", "2", "db")),
                "border");

        assertQuantity(labor, "45", QuantityUnit.SQUARE_METER);
        assertQuantity(material, "49.5", QuantityUnit.SQUARE_METER);
        assertQuantity(volume, "6.75", QuantityUnit.CUBIC_METER);
        assertQuantity(border, "30", QuantityUnit.METER);
    }

    @Test
    void calculatesPaintingAreaCoverageAndPackagePurchaseWithoutLosingRequiredLiters() {
        CalculationInput length = input("length", "5", "m");
        CalculationInput width = input("width", "4", "m");
        CalculationInput height = input("height", "2.7", "m");
        CalculationInput two = input("two", "2", "scalar");
        CalculationInput coats = input("coats", "2", "scalar");
        CalculationInput coverage = input("coverage", "10", "m2/l");
        CalculationInput packSize = input("pack", "2.5", "l");
        CalculationPlan plan = new CalculationPlan(
                List.of(length, width, height, two, coats, coverage, packSize),
                List.of(
                        step("two-lengths", CalculationOperation.MULTIPLY, "length", "two"),
                        step("other-lengths", CalculationOperation.MULTIPLY, "width", "two"),
                        step("perimeter", CalculationOperation.ADD, "two-lengths", "other-lengths"),
                        step("walls", CalculationOperation.MULTIPLY, "perimeter", "height"),
                        step("ceiling", CalculationOperation.AREA, "length", "width"),
                        step("paint-area", CalculationOperation.ADD, "walls", "ceiling"),
                        step("painted-layers", CalculationOperation.MULTIPLY, "paint-area", "coats"),
                        new CalculationStep("liters", CalculationOperation.DIVIDE,
                                List.of("painted-layers", "coverage"), "l", null, null),
                        step("can-ratio", CalculationOperation.DIVIDE, "liters", "pack"),
                        step("whole-cans", CalculationOperation.ROUND, List.of("can-ratio"), null, 0, RoundingMode.CEILING),
                        step("can-count", CalculationOperation.COUNT, "whole-cans"),
                        new CalculationStep("purchased-liters", CalculationOperation.MULTIPLY,
                                List.of("can-count", "pack"), "l", null, null)),
                "purchased-liters",
                List.of());

        CalculationResult result = calculator.calculate(plan);

        assertEquals(CalculationStatus.VALID, result.status());
        assertQuantity(trace(result, "walls"), "48.6", QuantityUnit.SQUARE_METER);
        assertQuantity(trace(result, "ceiling"), "20", QuantityUnit.SQUARE_METER);
        assertQuantity(trace(result, "paint-area"), "68.6", QuantityUnit.SQUARE_METER);
        assertQuantity(trace(result, "liters"), "13.72", QuantityUnit.LITER);
        assertQuantity(trace(result, "can-count"), "6", QuantityUnit.EACH);
        assertQuantity(result, "15", QuantityUnit.LITER);
    }

    @Test
    void doesNotSubtractUnknownWindowDimensionsAndKeepsLaborIndependentOfCoats() {
        CalculationResult result = calculate(
                input("wall-area", "48.6", "m2"),
                List.of(step("two-walls", CalculationOperation.DIRECT, "wall-area")),
                "two-walls");

        assertQuantity(result, "48.6", QuantityUnit.SQUARE_METER);
        assertFalse(result.issues().stream().anyMatch(issue -> issue.code().equals("WINDOW_SUBTRACTION")));
    }

    @Test
    void addsAndConvertsCompatibleUnitsButRejectsAreaVolumeAddition() {
        CalculationResult compatible = calculate(
                input("meters", "1", "m"),
                List.of(step("sum", CalculationOperation.ADD, "meters", "centimeters")),
                List.of(input("centimeters", "50", "cm")),
                "sum");
        CalculationResult incompatible = calculate(
                input("area", "2", "m2"),
                List.of(step("invalid", CalculationOperation.ADD, "area", "volume")),
                List.of(input("volume", "1", "m3")),
                "invalid");

        assertQuantity(compatible, "1.5", QuantityUnit.METER);
        assertEquals(CalculationStatus.INVALID, incompatible.status());
        assertNull(incompatible.quantity());
    }

    @Test
    void literAndCubicMeterArePhysicallyConvertible() {
        CalculationResult result = calculate(
                input("liters", "1000", "l"),
                List.of(new CalculationStep("cubic-meters", CalculationOperation.DIRECT,
                        List.of("liters"), "m3", null, null)),
                "cubic-meters");

        assertQuantity(result, "1", QuantityUnit.CUBIC_METER);
    }

    @Test
    void supportsSubtractionAndDivisionWithDeterministicPrecision() {
        CalculationResult difference = calculate(
                input("gross", "68.6", "m2"),
                List.of(step("net", CalculationOperation.SUBTRACT, "gross", "opening")),
                List.of(input("opening", "2.4", "m2")),
                "net");
        CalculationResult division = calculate(
                input("amount", "10", "scalar"),
                List.of(step("per-item", CalculationOperation.DIVIDE, "amount", "count")),
                List.of(input("count", "3", "scalar")),
                "per-item");

        assertQuantity(difference, "66.2", QuantityUnit.SQUARE_METER);
        assertEquals(CalculationStatus.VALID, division.status());
        assertEquals("3.333333333333", division.quantity().value().toPlainString());
        assertEquals(QuantityUnit.SCALAR, division.quantity().unit());
    }

    @Test
    void validatesPerimeterAndThreeSideVolume() {
        CalculationResult perimeter = calculate(
                input("a", "15", "m"),
                List.of(step("perimeter", CalculationOperation.PERIMETER, "a", "b")),
                List.of(input("b", "15", "m")),
                "perimeter");
        CalculationResult volume = calculate(
                input("a", "2", "m"),
                List.of(step("volume", CalculationOperation.VOLUME, "a", "b", "c")),
                List.of(input("b", "3", "m"), input("c", "4", "m")),
                "volume");

        assertQuantity(perimeter, "30", QuantityUnit.METER);
        assertQuantity(volume, "24", QuantityUnit.CUBIC_METER);
    }

    @Test
    void missingInputProducesNeedsInputWithNoFinalQuantity() {
        CalculationResult result = calculator.calculate(new CalculationPlan(
                List.of(input("area", null, "m2")),
                List.of(step("out", CalculationOperation.DIRECT, "area")),
                "out",
                List.of()));

        assertEquals(CalculationStatus.NEEDS_INPUT, result.status());
        assertNull(result.quantity());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("NEEDS_INPUT")));
    }

    @Test
    void unapprovedAiAssumptionAndReviewReasonCannotProduceFinalQuantity() {
        CalculationPlan unapproved = new CalculationPlan(
                List.of(new CalculationInput("depth", new BigDecimal("0.15"), "m", InputSource.AI_ASSUMPTION, false)),
                List.of(step("out", CalculationOperation.DIRECT, "depth")),
                "out",
                List.of());
        CalculationPlan review = new CalculationPlan(
                List.of(input("area", "45", "m2")),
                List.of(step("out", CalculationOperation.DIRECT, "area")),
                "out",
                List.of("Confirm the requested scope."));

        CalculationResult assumptionResult = calculator.calculate(unapproved);
        CalculationResult reviewResult = calculator.calculate(review);

        assertEquals(CalculationStatus.NEEDS_REVIEW, assumptionResult.status());
        assertEquals(CalculationStatus.NEEDS_REVIEW, reviewResult.status());
        assertNull(assumptionResult.quantity());
        assertNull(reviewResult.quantity());
    }

    @Test
    void approvedAssumptionCanBeUsedByThePureCalculationEngine() {
        CalculationResult result = calculator.calculate(new CalculationPlan(
                List.of(new CalculationInput("depth", new BigDecimal("15"), "cm", InputSource.AI_ASSUMPTION, true)),
                List.of(new CalculationStep("out", CalculationOperation.DIRECT,
                        List.of("depth"), "m", null, null)),
                "out",
                List.of()));

        assertQuantity(result, "0.15", QuantityUnit.METER);
    }

    @Test
    void roundsAndRoundsUpToPackageMultiples() {
        CalculationResult rounded = calculate(
                input("quantity", "3.14159", "l"),
                List.of(step("rounded", CalculationOperation.ROUND, List.of("quantity"), null, 2, RoundingMode.HALF_UP)),
                "rounded");
        CalculationResult packageAmount = calculate(
                input("needed", "13.72", "l"),
                List.of(new CalculationStep("purchase", CalculationOperation.ROUND_UP_TO_MULTIPLE,
                        List.of("needed", "pack"), "l", null, null)),
                List.of(input("pack", "2.5", "l")),
                "purchase");

        assertQuantity(rounded, "3.14", QuantityUnit.LITER);
        assertQuantity(packageAmount, "15", QuantityUnit.LITER);
    }

    @Test
    void countAndDimensionlessScalarRemainSemanticallyDistinct() {
        CalculationResult count = calculate(
                input("number", "40", "scalar"),
                List.of(step("count", CalculationOperation.COUNT, "number")),
                "count");
        CalculationResult invalidAddition = calculate(
                input("count", "2", "db"),
                List.of(step("invalid", CalculationOperation.ADD, "count", "scalar")),
                List.of(input("scalar", "2", "scalar")),
                "invalid");

        assertQuantity(count, "40", QuantityUnit.EACH);
        assertEquals(CalculationStatus.INVALID, invalidAddition.status());
    }

    @Test
    void calculatesElectricalPointCountsWithoutInferringCableLength() {
        CalculationPlan points = new CalculationPlan(
                List.of(input("sockets", "20", "db"), input("switches", "8", "db"),
                        input("light-points", "12", "db")),
                List.of(
                        new CalculationStep("socket-count", CalculationOperation.DIRECT,
                                List.of("sockets"), "db", null, null),
                        new CalculationStep("switch-count", CalculationOperation.DIRECT,
                                List.of("switches"), "db", null, null),
                        new CalculationStep("light-count", CalculationOperation.DIRECT,
                                List.of("light-points"), "db", null, null)),
                "socket-count",
                List.of());
        CalculationResult pointResult = calculator.calculate(points);
        CalculationResult missingCableLength = calculator.calculate(new CalculationPlan(
                List.of(input("cable-length", null, "m")),
                List.of(step("cable", CalculationOperation.DIRECT, "cable-length")),
                "cable",
                List.of()));

        assertQuantity(trace(pointResult, "socket-count"), "20", QuantityUnit.EACH);
        assertQuantity(trace(pointResult, "switch-count"), "8", QuantityUnit.EACH);
        assertQuantity(trace(pointResult, "light-count"), "12", QuantityUnit.EACH);
        assertEquals(CalculationStatus.NEEDS_INPUT, missingCableLength.status());
        assertNull(missingCableLength.quantity());
    }

    @Test
    void rejectsUnknownUnitsNullDivisionAndNegativeIntermediateValues() {
        CalculationResult unknownUnit = calculate(
                input("value", "2", "yards"),
                List.of(step("out", CalculationOperation.DIRECT, "value")),
                "out");
        CalculationResult divideByZero = calculate(
                input("value", "2", "m"),
                List.of(step("out", CalculationOperation.DIVIDE, "value", "zero")),
                List.of(input("zero", "0", "scalar")),
                "out");
        CalculationResult negative = calculate(
                input("small", "2", "m"),
                List.of(step("out", CalculationOperation.SUBTRACT, "small", "large")),
                List.of(input("large", "3", "m")),
                "out");

        assertEquals(CalculationStatus.INVALID, unknownUnit.status());
        assertEquals(CalculationStatus.INVALID, divideByZero.status());
        assertEquals(CalculationStatus.INVALID, negative.status());
        assertNull(unknownUnit.quantity());
        assertNull(divideByZero.quantity());
        assertNull(negative.quantity());
    }

    @Test
    void rejectsForwardReferencesDuplicateIdsAndExcessivePlanSize() {
        CalculationResult forward = calculator.calculate(new CalculationPlan(
                List.of(input("source", "2", "m")),
                List.of(step("first", CalculationOperation.ADD, "source", "later"),
                        step("later", CalculationOperation.DIRECT, "source")),
                "first",
                List.of()));
        CalculationResult duplicate = calculator.calculate(new CalculationPlan(
                List.of(input("same", "2", "m")),
                List.of(step("same", CalculationOperation.DIRECT, "same")),
                "same",
                List.of()));
        List<CalculationStep> excessive = java.util.stream.IntStream.range(0, 33)
                .mapToObj(index -> step("s" + index, CalculationOperation.DIRECT,
                        index == 0 ? "source" : "s" + (index - 1)))
                .toList();
        CalculationResult tooMany = calculator.calculate(new CalculationPlan(
                List.of(input("source", "2", "m")), excessive, "s32", List.of()));

        assertEquals(CalculationStatus.INVALID, forward.status());
        assertEquals(CalculationStatus.INVALID, duplicate.status());
        assertEquals(CalculationStatus.INVALID, tooMany.status());
    }

    @Test
    void rejectsNegativeHugeAndInvalidlyScaledNumbers() {
        CalculationResult negative = calculator.calculate(new CalculationPlan(
                List.of(input("value", "-1", "m")),
                List.of(step("out", CalculationOperation.DIRECT, "value")),
                "out", List.of()));
        CalculationResult huge = calculator.calculate(new CalculationPlan(
                List.of(new CalculationInput("value", new BigDecimal("1000000000001"), "m",
                        InputSource.CUSTOMER_EMAIL, true)),
                List.of(step("out", CalculationOperation.DIRECT, "value")),
                "out", List.of()));

        assertEquals(CalculationStatus.INVALID, negative.status());
        assertEquals(CalculationStatus.INVALID, huge.status());
    }

    @Test
    void repeatedCalculationIsDeterministicAndExplainsEveryStep() {
        CalculationPlan plan = new CalculationPlan(
                List.of(input("area", "45", "m2"), input("depth", "0.15", "m")),
                List.of(step("volume", CalculationOperation.VOLUME, "area", "depth")),
                "volume",
                List.of());

        CalculationResult first = calculator.calculate(plan);
        CalculationResult second = calculator.calculate(plan);

        assertEquals(first, second);
        assertEquals(1, first.trace().size());
        assertNotNull(first.trace().get(0).result());
    }

    private CalculationResult calculate(
            CalculationInput first, List<CalculationStep> steps, String outputReference) {
        return calculator.calculate(new CalculationPlan(List.of(first), steps, outputReference, List.of()));
    }

    private CalculationResult calculate(
            CalculationInput first, List<CalculationStep> steps, List<CalculationInput> additional, String outputReference) {
        return calculator.calculate(new CalculationPlan(
                java.util.stream.Stream.concat(java.util.stream.Stream.of(first), additional.stream()).toList(),
                steps,
                outputReference,
                List.of()));
    }

    private CalculationInput input(String id, String value, String unit) {
        return new CalculationInput(id, value == null ? null : new BigDecimal(value),
                unit, InputSource.CUSTOMER_EMAIL, true);
    }

    private CalculationStep step(String id, CalculationOperation operation, String... operands) {
        return new CalculationStep(id, operation, List.of(operands));
    }

    private CalculationStep step(
            String id, CalculationOperation operation, List<String> operands,
            String outputUnit, Integer scale, RoundingMode roundingMode) {
        return new CalculationStep(id, operation, operands, outputUnit, scale, roundingMode);
    }

    private Quantity trace(CalculationResult result, String stepId) {
        return result.trace().stream()
                .filter(entry -> entry.stepId().equals(stepId))
                .findFirst()
                .orElseThrow()
                .result();
    }

    private void assertQuantity(CalculationResult result, String expected, QuantityUnit unit) {
        assertEquals(CalculationStatus.VALID, result.status(), result.issues().toString());
        assertNotNull(result.quantity());
        assertQuantity(result.quantity(), expected, unit);
    }

    private void assertQuantity(Quantity quantity, String expected, QuantityUnit unit) {
        assertNotNull(quantity);
        assertEquals(0, quantity.value().compareTo(new BigDecimal(expected)));
        assertEquals(unit, quantity.unit());
    }
}
