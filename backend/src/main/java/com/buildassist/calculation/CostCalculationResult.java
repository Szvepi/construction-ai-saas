package com.buildassist.calculation;

import java.util.List;
import java.util.Map;

public record CostCalculationResult(
        PricingStatus pricingStatus,
        NetMoney netQuotePrice,
        Map<String, NetMoney> netLineTotals,
        NetMoney availableDirectCost,
        NetMargin estimatedMargin,
        CostCoverage costCoverage,
        List<String> missingCostIds,
        List<PricingIssue> issues) {

    public CostCalculationResult {
        netLineTotals = Map.copyOf(netLineTotals);
        missingCostIds = List.copyOf(missingCostIds);
        issues = List.copyOf(issues);
        if (pricingStatus != PricingStatus.VALID && netQuotePrice != null) {
            throw new IllegalArgumentException("An invalid or incomplete quote cannot have a final price.");
        }
        if (costCoverage == CostCoverage.UNAVAILABLE && estimatedMargin != null) {
            throw new IllegalArgumentException("A margin cannot be calculated without cost data.");
        }
    }
}
