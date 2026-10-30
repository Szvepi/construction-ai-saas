package com.buildassist.calculation;

import java.util.Objects;

public record LaborRequirement(
        String id,
        String workItemId,
        Quantity customerChargeQuantity,
        NetMoney estimatedOwnUnitCost) {

    public LaborRequirement {
        WorkItem.requireId(id);
        WorkItem.requireId(workItemId);
        Objects.requireNonNull(customerChargeQuantity, "customerChargeQuantity");
        if (customerChargeQuantity.value().signum() < 0) {
            throw new IllegalArgumentException("Labor quantity cannot be negative.");
        }
    }
}
