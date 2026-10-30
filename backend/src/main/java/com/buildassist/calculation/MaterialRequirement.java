package com.buildassist.calculation;

import java.util.Objects;

public record MaterialRequirement(
        String id,
        String workItemId,
        Quantity requiredQuantity,
        Quantity allowanceAdjustedQuantity,
        Quantity procurementQuantity,
        Quantity customerChargedQuantity,
        NetMoney netProcurementUnitCost) {

    public MaterialRequirement {
        WorkItem.requireId(id);
        WorkItem.requireId(workItemId);
        Objects.requireNonNull(requiredQuantity, "requiredQuantity");
        Objects.requireNonNull(allowanceAdjustedQuantity, "allowanceAdjustedQuantity");
        Objects.requireNonNull(procurementQuantity, "procurementQuantity");
        Objects.requireNonNull(customerChargedQuantity, "customerChargedQuantity");
        requireCompatible(requiredQuantity, allowanceAdjustedQuantity);
        requireCompatible(requiredQuantity, procurementQuantity);
        requireCompatible(requiredQuantity, customerChargedQuantity);
        if (requiredQuantity.value().signum() < 0
                || allowanceAdjustedQuantity.value().signum() < 0
                || procurementQuantity.value().signum() < 0
                || customerChargedQuantity.value().signum() < 0) {
            throw new IllegalArgumentException("Material quantities cannot be negative.");
        }
    }

    private static void requireCompatible(Quantity left, Quantity right) {
        right.convertTo(left.unit());
    }
}
