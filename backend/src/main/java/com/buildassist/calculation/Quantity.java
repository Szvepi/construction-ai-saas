package com.buildassist.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Quantity(BigDecimal value, QuantityUnit unit) {
    static final int MAX_PRECISION = 24;
    static final int MAX_SCALE = 12;
    static final BigDecimal MAX_ABSOLUTE_VALUE = new BigDecimal("1000000000000");

    public Quantity {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(unit, "unit");
        if (value.precision() > MAX_PRECISION || Math.max(0, value.scale()) > MAX_SCALE
                || value.abs().compareTo(MAX_ABSOLUTE_VALUE) > 0) {
            throw new IllegalArgumentException("Quantity exceeds the supported numeric range.");
        }
    }

    public Quantity convertTo(QuantityUnit target) {
        Objects.requireNonNull(target, "target");
        if (!unit.dimension().equals(target.dimension()) || unit.semantic() != target.semantic()) {
            throw new IllegalArgumentException("Incompatible quantity units.");
        }
        BigDecimal baseValue = value.multiply(unit.toBaseFactor());
        BigDecimal converted = baseValue.divide(target.toBaseFactor(), QuantityUnitScale.SCALE, RoundingMode.HALF_UP);
        return new Quantity(converted.stripTrailingZeros(), target);
    }

    static Quantity fromBase(BigDecimal baseValue, UnitDimension dimension) {
        QuantityUnit target = QuantityUnit.forDimension(dimension, QuantityUnit.Semantic.PHYSICAL);
        BigDecimal converted = baseValue.divide(target.toBaseFactor(), QuantityUnitScale.SCALE, RoundingMode.HALF_UP);
        return new Quantity(converted.stripTrailingZeros(), target);
    }

    static Quantity canonical(BigDecimal baseValue, UnitDimension dimension, QuantityUnit.Semantic semantic) {
        QuantityUnit target = QuantityUnit.forDimension(dimension, semantic);
        BigDecimal converted = baseValue.divide(target.toBaseFactor(), QuantityUnitScale.SCALE, RoundingMode.HALF_UP);
        return new Quantity(converted.stripTrailingZeros(), target);
    }

    private static final class QuantityUnitScale {
        private static final int SCALE = 12;
    }
}
