package com.buildassist.calculation;

import java.util.List;
import java.util.Objects;

public record QuoteLineItem(
        String id,
        String workItemId,
        SettlementMode settlementMode,
        List<String> requirementIds,
        Quantity customerChargeQuantity,
        NetMoney netUnitPrice) {

    public QuoteLineItem {
        WorkItem.requireId(id);
        WorkItem.requireId(workItemId);
        Objects.requireNonNull(settlementMode, "settlementMode");
        requirementIds = requirementIds == null ? null : List.copyOf(requirementIds);
        Objects.requireNonNull(customerChargeQuantity, "customerChargeQuantity");
        if (customerChargeQuantity.value().signum() < 0) {
            throw new IllegalArgumentException("Quote quantity cannot be negative.");
        }
    }
}
