package com.buildassist.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record NetMargin(BigDecimal huf) {
    public NetMargin {
        Objects.requireNonNull(huf, "huf");
        if (huf.precision() > 24 || huf.abs().compareTo(Quantity.MAX_ABSOLUTE_VALUE) > 0) {
            throw new IllegalArgumentException("Net HUF margin is outside the supported range.");
        }
        huf = huf.setScale(0, RoundingMode.HALF_UP);
    }
}
