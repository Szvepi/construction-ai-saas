package com.buildassist.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record NetMoney(BigDecimal huf) {
    public NetMoney {
        Objects.requireNonNull(huf, "huf");
        if (huf.signum() < 0 || huf.precision() > 24
                || huf.abs().compareTo(Quantity.MAX_ABSOLUTE_VALUE) > 0) {
            throw new IllegalArgumentException("Net HUF amount must be non-negative and within range.");
        }
        huf = huf.setScale(0, RoundingMode.HALF_UP);
    }

    public static NetMoney of(BigDecimal huf) {
        return new NetMoney(huf);
    }

    public static NetMoney zero() {
        return new NetMoney(BigDecimal.ZERO);
    }
}
