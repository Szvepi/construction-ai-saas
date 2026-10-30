package com.buildassist.calculation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CostCalculationService {

    public CostCalculationResult calculate(CostCalculationRequest request) {
        List<PricingIssue> issues = new ArrayList<>(validate(request));
        if (!issues.isEmpty()) {
            return invalid(issues);
        }

        Map<String, WorkItem> works = index(request.workItems(), WorkItem::id);
        Map<String, RequirementRef> requirements = new HashMap<>();
        request.materialRequirements().forEach(item ->
                requirements.put(item.id(), new RequirementRef(item.workItemId(), ChargeComponent.MATERIAL)));
        request.laborRequirements().forEach(item ->
                requirements.put(item.id(), new RequirementRef(item.workItemId(), ChargeComponent.LABOR)));
        request.otherCosts().forEach(item ->
                requirements.put(item.id(), new RequirementRef(item.workItemId(), ChargeComponent.OTHER)));

        List<PricingIssue> relationshipIssues = validateQuoteRelationships(request, works, requirements);
        if (!relationshipIssues.isEmpty()) {
            return invalid(relationshipIssues);
        }

        boolean missingPrice = request.quoteLineItems().stream().anyMatch(item -> item.netUnitPrice() == null);
        BigDecimal quoteTotal = BigDecimal.ZERO;
        Map<String, NetMoney> lineTotals = new HashMap<>();
        if (!missingPrice) {
            for (QuoteLineItem item : request.quoteLineItems()) {
                NetMoney lineTotal = amount(item.customerChargeQuantity(), item.netUnitPrice());
                lineTotals.put(item.id(), lineTotal);
                quoteTotal = quoteTotal.add(lineTotal.huf());
            }
        } else {
            request.quoteLineItems().stream()
                    .filter(item -> item.netUnitPrice() == null)
                    .forEach(item -> issues.add(new PricingIssue(
                            "UNIT_PRICE_REQUIRED", "A net selling unit price is required.", item.id())));
        }

        List<String> missingCostIds = new ArrayList<>();
        BigDecimal knownCosts = BigDecimal.ZERO;
        boolean expectedCostFound = false;

        for (MaterialRequirement requirement : request.materialRequirements()) {
            WorkItem work = works.get(requirement.workItemId());
            if (work.settlementMode() == SettlementMode.CUSTOMER_SUPPLIED_MATERIAL) {
                continue;
            }
            expectedCostFound = true;
            if (requirement.netProcurementUnitCost() == null) {
                missingCostIds.add(requirement.id());
            } else {
                knownCosts = knownCosts.add(amount(
                        requirement.procurementQuantity(), requirement.netProcurementUnitCost()).huf());
            }
        }
        for (LaborRequirement requirement : request.laborRequirements()) {
            expectedCostFound = true;
            if (requirement.estimatedOwnUnitCost() == null) {
                missingCostIds.add(requirement.id());
            } else {
                knownCosts = knownCosts.add(amount(
                        requirement.customerChargeQuantity(), requirement.estimatedOwnUnitCost()).huf());
            }
        }
        for (OtherCost cost : request.otherCosts()) {
            expectedCostFound = true;
            if (cost.directCost() == null) {
                missingCostIds.add(cost.id());
            } else {
                knownCosts = knownCosts.add(cost.directCost().huf());
            }
        }

        CostCoverage costCoverage;
        if (!expectedCostFound || missingCostIds.size() == countApplicableCosts(request, works)) {
            costCoverage = CostCoverage.UNAVAILABLE;
        } else if (!missingCostIds.isEmpty()) {
            costCoverage = CostCoverage.PARTIAL;
        } else {
            costCoverage = CostCoverage.COMPLETE;
        }

        NetMoney quotePrice = missingPrice ? null : NetMoney.of(quoteTotal);
        NetMoney availableCost = costCoverage == CostCoverage.UNAVAILABLE ? null : NetMoney.of(knownCosts);
        NetMargin margin = quotePrice == null || availableCost == null
                ? null : new NetMargin(quotePrice.huf().subtract(availableCost.huf()));
        PricingStatus pricingStatus = missingPrice ? PricingStatus.NEEDS_INPUT : PricingStatus.VALID;
        return new CostCalculationResult(
                pricingStatus,
                quotePrice,
                lineTotals,
                availableCost,
                margin,
                costCoverage,
                missingCostIds,
                issues);
    }

    private List<PricingIssue> validate(CostCalculationRequest request) {
        List<PricingIssue> issues = new ArrayList<>();
        if (request == null) {
            return List.of(new PricingIssue("REQUEST_REQUIRED", "A cost calculation request is required.", null));
        }
        if (request.workItems() == null || request.materialRequirements() == null
                || request.laborRequirements() == null || request.otherCosts() == null
                || request.quoteLineItems() == null) {
            return List.of(new PricingIssue("COLLECTION_REQUIRED", "All cost calculation collections are required.", null));
        }
        uniqueIds(request.workItems().stream().map(WorkItem::id).toList(), "work", issues);
        Set<String> workIds = new HashSet<>(request.workItems().stream().map(WorkItem::id).toList());
        List<String> requirementIds = new ArrayList<>();
        request.materialRequirements().forEach(item -> {
            requirementIds.add(item.id());
            requireWork(workIds, item.workItemId(), item.id(), issues);
        });
        request.laborRequirements().forEach(item -> {
            requirementIds.add(item.id());
            requireWork(workIds, item.workItemId(), item.id(), issues);
        });
        request.otherCosts().forEach(item -> {
            requirementIds.add(item.id());
            requireWork(workIds, item.workItemId(), item.id(), issues);
        });
        uniqueIds(requirementIds, "requirement", issues);
        uniqueIds(request.quoteLineItems().stream().map(QuoteLineItem::id).toList(), "quote line", issues);
        return List.copyOf(issues);
    }

    private List<PricingIssue> validateQuoteRelationships(
            CostCalculationRequest request,
            Map<String, WorkItem> works,
            Map<String, RequirementRef> requirements) {
        List<PricingIssue> issues = new ArrayList<>();
        Set<String> chargedRequirementIds = new HashSet<>();
        for (QuoteLineItem line : request.quoteLineItems()) {
            if (line.requirementIds() == null || line.requirementIds().isEmpty()) {
                issues.add(new PricingIssue(
                        "REQUIREMENTS_REQUIRED",
                        "A quote line must explicitly cover at least one requirement.",
                        line.id()));
                continue;
            }
            WorkItem work = works.get(line.workItemId());
            if (work == null) {
                issues.add(new PricingIssue("UNKNOWN_WORK", "The quote line references an unknown work item.", line.id()));
                continue;
            }
            if (line.settlementMode() != work.settlementMode()) {
                issues.add(new PricingIssue(
                        "SETTLEMENT_MODE_MISMATCH",
                        "The quote line settlement mode must match its work item.",
                        line.id()));
            }
            EnumSet<ChargeComponent> components = EnumSet.noneOf(ChargeComponent.class);
            for (String requirementId : line.requirementIds()) {
                RequirementRef requirement = requirements.get(requirementId);
                if (requirement == null) {
                    issues.add(new PricingIssue(
                            "UNKNOWN_REQUIREMENT",
                            "The quote line references an unknown requirement.",
                            requirementId));
                    continue;
                }
                if (!Objects.equals(requirement.workItemId(), line.workItemId())) {
                    issues.add(new PricingIssue(
                            "CROSS_WORK_REQUIREMENT",
                            "A quote line may only cover requirements belonging to its work item.",
                            requirementId));
                }
                if (!chargedRequirementIds.add(requirementId)) {
                    issues.add(new PricingIssue(
                            "DUPLICATE_CHARGE",
                            "A requirement cannot be charged by more than one quote line.",
                            requirementId));
                }
                components.add(requirement.component());
            }
            if (line.settlementMode() != SettlementMode.MATERIAL_AND_LABOR
                    && components.contains(ChargeComponent.MATERIAL)) {
                issues.add(new PricingIssue(
                        "MATERIAL_CHARGE_NOT_ALLOWED",
                        "This settlement mode does not allow charging the customer for material.",
                        line.id()));
            }
        }
        return List.copyOf(issues);
    }

    private int countApplicableCosts(CostCalculationRequest request, Map<String, WorkItem> works) {
        int count = 0;
        for (MaterialRequirement item : request.materialRequirements()) {
            if (works.get(item.workItemId()).settlementMode() != SettlementMode.CUSTOMER_SUPPLIED_MATERIAL) {
                count++;
            }
        }
        return count + request.laborRequirements().size() + request.otherCosts().size();
    }

    private NetMoney amount(Quantity quantity, NetMoney unitPrice) {
        return NetMoney.of(quantity.value().multiply(unitPrice.huf()));
    }

    private void uniqueIds(List<String> ids, String kind, List<PricingIssue> issues) {
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank() || !seen.add(id)) {
                issues.add(new PricingIssue("DUPLICATE_OR_INVALID_ID", "Each " + kind + " identifier must be unique.", id));
            }
        }
    }

    private void requireWork(Set<String> workIds, String workId, String reference, List<PricingIssue> issues) {
        if (!workIds.contains(workId)) {
            issues.add(new PricingIssue("UNKNOWN_WORK", "A requirement references an unknown work item.", reference));
        }
    }

    private <T> Map<String, T> index(List<T> values, java.util.function.Function<T, String> key) {
        Map<String, T> indexed = new HashMap<>();
        values.forEach(value -> indexed.put(key.apply(value), value));
        return indexed;
    }

    private CostCalculationResult invalid(List<PricingIssue> issues) {
        return new CostCalculationResult(
                PricingStatus.INVALID, null, Map.of(), null, null, CostCoverage.UNAVAILABLE, List.of(), issues);
    }

    private record RequirementRef(String workItemId, ChargeComponent component) {
    }
}
