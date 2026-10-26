package com.buildassist.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public enum CalculationStrategy {

    DIRECT,              // Nincs módosítás (pl. fűnyírás m2-re)
    WALL_SURFACE_3X,     // Alapterületből csinál falfelületet (Festő)
    ROOM_PERIMETER,      // Alapterületből szoba kerületet számol (Szegélyléc, lábazat)
    VOLUME_BY_THICKNESS, // Területből köbmétert csinál fix vastagsággal (Kőműves)
    WASTE_PERCENTAGE_10;  // Automatikusan rátesz 10% anyagveszteséget (Burkoló)

    public boolean supportsUnits(String inputUnit, String outputUnit) {
        String input = normalizeUnit(inputUnit);
        String output = normalizeUnit(outputUnit);
        if (input == null || output == null) {
            return this == DIRECT;
        }
        return switch (this) {
            case DIRECT -> true;
            case WALL_SURFACE_3X -> isArea(input) && isArea(output);
            case WASTE_PERCENTAGE_10 -> input.equals(output);
            case ROOM_PERIMETER -> input.equals(output) && isLength(output)
                    || isArea(input) && isLength(output);
            case VOLUME_BY_THICKNESS -> isArea(input) && isVolume(output);
        };
    }

    public BigDecimal adjustQuantity(BigDecimal baseQuantity, String inputUnit, String outputUnit) {
        if (!supportsUnits(inputUnit, outputUnit)) {
            return baseQuantity;
        }
        return switch (this) {
            case WALL_SURFACE_3X -> baseQuantity.multiply(BigDecimal.valueOf(3));
            case WASTE_PERCENTAGE_10 -> baseQuantity.multiply(new BigDecimal("1.10"))
                    .setScale(2, RoundingMode.HALF_UP);
            case ROOM_PERIMETER -> {
                if (!isArea(normalizeUnit(inputUnit))) {
                    yield baseQuantity;
                }
                double area = baseQuantity.doubleValue();
                yield area <= 0 ? baseQuantity
                        : BigDecimal.valueOf(Math.sqrt(area) * 4).setScale(2, RoundingMode.HALF_UP);
            }
            case VOLUME_BY_THICKNESS -> baseQuantity.multiply(new BigDecimal("0.15"))
                    .setScale(2, RoundingMode.HALF_UP);
            case DIRECT -> baseQuantity;
        };
    }

    public BigDecimal adjustQuantity(BigDecimal baseQuantity, String inputUnit) {
        String normalizedInput = normalizeUnit(inputUnit);
        String outputUnit = switch (this) {
            case ROOM_PERIMETER -> isArea(normalizedInput) ? "m" : inputUnit;
            case VOLUME_BY_THICKNESS -> isArea(normalizedInput) ? "m3" : inputUnit;
            case WALL_SURFACE_3X -> isArea(normalizedInput) ? "m2" : inputUnit;
            case DIRECT, WASTE_PERCENTAGE_10 -> inputUnit;
        };
        return adjustQuantity(baseQuantity, inputUnit, outputUnit);
    }

    private static String normalizeUnit(String unit) {
        if (unit == null || unit.isBlank()) {
            return null;
        }
        String normalized = unit.trim().toLowerCase(Locale.ROOT)
                .replace("²", "2")
                .replace("³", "3")
                .replace("^", "")
                .replace(" ", "");
        return switch (normalized) {
            case "m", "fm", "folyóméter" -> "m";
            case "m2", "négyzetméter" -> "m2";
            case "m3", "köbméter" -> "m3";
            case "db", "darab" -> "db";
            case "kg", "kilogramm" -> "kg";
            case "t", "tonna" -> "t";
            case "óra", "ora", "h" -> "h";
            default -> null;
        };
    }

    private static boolean isArea(String unit) {
        return "m2".equals(unit);
    }

    private static boolean isLength(String unit) {
        return "m".equals(unit);
    }

    private static boolean isVolume(String unit) {
        return "m3".equals(unit);
    }
}
