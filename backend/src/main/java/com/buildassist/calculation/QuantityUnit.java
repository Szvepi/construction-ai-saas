package com.buildassist.calculation;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

public enum QuantityUnit {
    MILLIMETER("mm", new UnitDimension(1, 0, 0, 0), new BigDecimal("0.001"), Semantic.PHYSICAL),
    CENTIMETER("cm", new UnitDimension(1, 0, 0, 0), new BigDecimal("0.01"), Semantic.PHYSICAL),
    METER("m", new UnitDimension(1, 0, 0, 0), BigDecimal.ONE, Semantic.PHYSICAL),
    SQUARE_METER("m2", new UnitDimension(2, 0, 0, 0), BigDecimal.ONE, Semantic.PHYSICAL),
    CUBIC_METER("m3", new UnitDimension(3, 0, 0, 0), BigDecimal.ONE, Semantic.PHYSICAL),
    LITER("l", new UnitDimension(3, 0, 0, 0), new BigDecimal("0.001"), Semantic.PHYSICAL),
    SQUARE_METER_PER_LITER("m2/l", new UnitDimension(-1, 0, 0, 0),
            new BigDecimal("1000"), Semantic.PHYSICAL),
    EACH("db", new UnitDimension(0, 0, 0, 1), BigDecimal.ONE, Semantic.COUNT),
    PERCENT("%", new UnitDimension(0, 0, 0, 0), new BigDecimal("0.01"), Semantic.PERCENT),
    SCALAR("scalar", new UnitDimension(0, 0, 0, 0), BigDecimal.ONE, Semantic.SCALAR),
    KILOGRAM("kg", new UnitDimension(0, 1, 0, 0), BigDecimal.ONE, Semantic.PHYSICAL),
    HOUR("h", new UnitDimension(0, 0, 1, 0), BigDecimal.ONE, Semantic.PHYSICAL);

    private static final Map<String, QuantityUnit> CODES = Map.ofEntries(
            Map.entry("mm", MILLIMETER),
            Map.entry("cm", CENTIMETER),
            Map.entry("m", METER),
            Map.entry("fm", METER),
            Map.entry("folyóméter", METER),
            Map.entry("folyometer", METER),
            Map.entry("m2", SQUARE_METER),
            Map.entry("m²", SQUARE_METER),
            Map.entry("m3", CUBIC_METER),
            Map.entry("m³", CUBIC_METER),
            Map.entry("l", LITER),
            Map.entry("liter", LITER),
            Map.entry("litre", LITER),
            Map.entry("m2/l", SQUARE_METER_PER_LITER),
            Map.entry("m²/l", SQUARE_METER_PER_LITER),
            Map.entry("db", EACH),
            Map.entry("darab", EACH),
            Map.entry("alkalom", EACH),
            Map.entry("%", PERCENT),
            Map.entry("percent", PERCENT),
            Map.entry("százalék", PERCENT),
            Map.entry("szazalek", PERCENT),
            Map.entry("scalar", SCALAR),
            Map.entry("1", SCALAR),
            Map.entry("kg", KILOGRAM),
            Map.entry("h", HOUR),
            Map.entry("óra", HOUR),
            Map.entry("ora", HOUR));

    private final String code;
    private final UnitDimension dimension;
    private final BigDecimal toBaseFactor;
    private final Semantic semantic;

    QuantityUnit(String code, UnitDimension dimension, BigDecimal toBaseFactor, Semantic semantic) {
        this.code = code;
        this.dimension = dimension;
        this.toBaseFactor = toBaseFactor;
        this.semantic = semantic;
    }

    public String code() {
        return code;
    }

    UnitDimension dimension() {
        return dimension;
    }

    BigDecimal toBaseFactor() {
        return toBaseFactor;
    }

    Semantic semantic() {
        return semantic;
    }

    public static QuantityUnit fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A quantity unit is required.");
        }
        QuantityUnit unit = CODES.get(code.trim().toLowerCase(Locale.ROOT));
        if (unit == null) {
            throw new IllegalArgumentException("Unknown quantity unit: " + code);
        }
        return unit;
    }

    static QuantityUnit forDimension(UnitDimension dimension, Semantic semantic) {
        if (semantic == Semantic.COUNT && dimension.equals(EACH.dimension)) {
            return EACH;
        }
        if (semantic == Semantic.PERCENT && dimension.equals(PERCENT.dimension)) {
            return PERCENT;
        }
        if (semantic == Semantic.SCALAR && dimension.equals(SCALAR.dimension)) {
            return SCALAR;
        }
        if (dimension.equals(METER.dimension)) return METER;
        if (dimension.equals(SQUARE_METER.dimension)) return SQUARE_METER;
        if (dimension.equals(CUBIC_METER.dimension)) return CUBIC_METER;
        if (dimension.equals(SQUARE_METER_PER_LITER.dimension)) return SQUARE_METER_PER_LITER;
        if (dimension.equals(KILOGRAM.dimension)) return KILOGRAM;
        if (dimension.equals(HOUR.dimension)) return HOUR;
        throw new IllegalArgumentException("The resulting unit dimension is not supported.");
    }

    enum Semantic {
        PHYSICAL,
        COUNT,
        PERCENT,
        SCALAR
    }
}
