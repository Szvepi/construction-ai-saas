package com.buildassist.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public enum CalculationStrategy {

    DIRECT,              // Nincs módosítás (pl. fűnyírás m2-re)
    WALL_SURFACE_3X,     // Alapterületből csinál falfelületet (Festő)
    ROOM_PERIMETER,      // Alapterületből szoba kerületet számol (Szegélyléc, lábazat)
    VOLUME_BY_THICKNESS, // Területből köbmétert csinál fix vastagsággal (Kőműves)
    WASTE_PERCENTAGE_10;  // Automatikusan rátesz 10% anyagveszteséget (Burkoló)

    public BigDecimal adjustQuantity(BigDecimal baseQuantity) {
        return switch (this) {
            case WALL_SURFACE_3X -> baseQuantity.multiply(BigDecimal.valueOf(3));
            case WASTE_PERCENTAGE_10 -> baseQuantity.multiply(new BigDecimal("1.10"))
                    .setScale(2, RoundingMode.HALF_UP);
            case ROOM_PERIMETER -> {
                double area = baseQuantity.doubleValue();
                yield area <= 0 ? baseQuantity
                        : BigDecimal.valueOf(Math.sqrt(area) * 4).setScale(2, RoundingMode.HALF_UP);
            }
            case VOLUME_BY_THICKNESS -> baseQuantity.multiply(new BigDecimal("0.15"))
                    .setScale(2, RoundingMode.HALF_UP);
            case DIRECT -> baseQuantity;
        };
    }
}
