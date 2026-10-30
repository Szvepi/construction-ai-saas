package com.buildassist.calculation;

record UnitDimension(int length, int mass, int time, int count) {

    UnitDimension multiply(UnitDimension other) {
        return new UnitDimension(
                length + other.length,
                mass + other.mass,
                time + other.time,
                count + other.count);
    }

    UnitDimension divide(UnitDimension other) {
        return new UnitDimension(
                length - other.length,
                mass - other.mass,
                time - other.time,
                count - other.count);
    }
}
