package com.buildassist.calculation;

import java.util.Objects;

public record WorkItem(
        String id,
        String description,
        Quantity workQuantity,
        SettlementMode settlementMode) {

    public WorkItem {
        requireId(id);
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(settlementMode, "settlementMode");
        if (description.isBlank()) {
            throw new IllegalArgumentException("A work description is required.");
        }
        if (workQuantity != null && workQuantity.value().signum() < 0) {
            throw new IllegalArgumentException("Work quantity cannot be negative.");
        }
    }

    static void requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}")) {
            throw new IllegalArgumentException("A valid identifier is required.");
        }
    }
}
