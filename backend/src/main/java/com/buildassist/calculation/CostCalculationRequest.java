package com.buildassist.calculation;

import java.util.List;

public record CostCalculationRequest(
        List<WorkItem> workItems,
        List<MaterialRequirement> materialRequirements,
        List<LaborRequirement> laborRequirements,
        List<OtherCost> otherCosts,
        List<QuoteLineItem> quoteLineItems) {

    public CostCalculationRequest {
        workItems = workItems == null ? null : List.copyOf(workItems);
        materialRequirements = materialRequirements == null ? null : List.copyOf(materialRequirements);
        laborRequirements = laborRequirements == null ? null : List.copyOf(laborRequirements);
        otherCosts = otherCosts == null ? null : List.copyOf(otherCosts);
        quoteLineItems = quoteLineItems == null ? null : List.copyOf(quoteLineItems);
    }
}
