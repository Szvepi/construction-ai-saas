package com.buildassist.calculation;

import java.math.RoundingMode;

public record QuantityRoundingPolicy(int scale, RoundingMode mode) {
}
