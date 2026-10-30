package com.buildassist.service;

import com.buildassist.calculation.CostCalculationRequest;
import com.buildassist.calculation.CostCalculationResult;
import com.buildassist.calculation.CostCalculationService;
import com.buildassist.calculation.DraftQuoteSnapshot;
import com.buildassist.calculation.LaborRequirement;
import com.buildassist.calculation.MaterialRequirement;
import com.buildassist.calculation.NetMoney;
import com.buildassist.calculation.OtherCost;
import com.buildassist.calculation.QuoteLineItem;
import com.buildassist.calculation.WorkItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class DraftQuotePricingService {

    private final CostCalculationService costCalculationService;

    public DraftQuotePricingService() {
        this(new CostCalculationService());
    }

    DraftQuotePricingService(CostCalculationService costCalculationService) {
        this.costCalculationService = Objects.requireNonNull(costCalculationService, "costCalculationService");
    }

    public RepricingResult reprice(DraftQuoteSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<WorkItem> works = snapshot.workItems().stream()
                .map(work -> new WorkItem(work.workId(), work.originalRequest(),
                        work.customerRequestedQuantity(), work.settlementMode()))
                .toList();
        List<MaterialRequirement> materials = new ArrayList<>();
        List<LaborRequirement> labor = new ArrayList<>();
        List<OtherCost> otherCosts = new ArrayList<>();
        List<QuoteLineItem> quoteLines = new ArrayList<>();

        for (DraftQuoteSnapshot.QuoteLineSnapshot line : snapshot.lineItems()) {
            if (line.calculationResult().status()
                    != com.buildassist.calculation.CalculationStatus.VALID) {
                continue;
            }
            for (DraftQuoteSnapshot.RequirementSnapshot requirement : line.requirements()) {
                switch (requirement.component()) {
                    case MATERIAL -> {
                        BigDecimal charged = requirement.customerChargeQuantity().value();
                        com.buildassist.calculation.Quantity allowanceQuantity =
                                requirement.allowanceAdjustedQuantity();
                        materials.add(new MaterialRequirement(
                                requirement.requirementId(),
                                line.workId(),
                                requirement.requiredQuantity(),
                                allowanceQuantity == null ? requirement.requiredQuantity() : allowanceQuantity,
                                requirement.procurementQuantity(),
                                requirement.customerChargeQuantity(),
                                requirement.netUnitCost() == null ? null
                                        : NetMoney.of(requirement.netUnitCost())));
                    }
                    case LABOR -> labor.add(new LaborRequirement(
                            requirement.requirementId(),
                            line.workId(),
                            requirement.customerChargeQuantity(),
                            requirement.netUnitCost() == null ? null
                                    : NetMoney.of(requirement.netUnitCost())));
                    case OTHER -> otherCosts.add(new OtherCost(
                            requirement.requirementId(),
                            line.workId(),
                            requirement.directCost() == null ? null
                                    : NetMoney.of(requirement.directCost())));
                }
            }
            if (line.chargeable()) {
                if (line.catalogItem() == null || line.catalogItem().netSellingUnitPrice() == null
                        || line.customerChargeQuantity() == null) {
                    continue;
                }
                quoteLines.add(new QuoteLineItem(
                        line.lineItemId(),
                        line.workId(),
                        line.settlementMode(),
                        line.requirements().stream()
                                .map(DraftQuoteSnapshot.RequirementSnapshot::requirementId)
                                .toList(),
                        line.userOverrideQuantity() == null
                                ? line.customerChargeQuantity() : line.userOverrideQuantity(),
                        NetMoney.of(line.manualNetUnitPrice() == null
                                ? line.catalogItem().netSellingUnitPrice() : line.manualNetUnitPrice())));
            }
        }

        CostCalculationResult pricing = costCalculationService.calculate(new CostCalculationRequest(
                works, materials, labor, otherCosts, quoteLines));
        Map<String, NetMoney> lineTotals = pricing.netLineTotals();
        List<DraftQuoteSnapshot.QuoteLineSnapshot> pricedLines = snapshot.lineItems().stream()
                .map(line -> {
                    if (line.calculationResult().status()
                            != com.buildassist.calculation.CalculationStatus.VALID) {
                        return line;
                    }
                    BigDecimal total = line.chargeable()
                            ? (lineTotals.get(line.lineItemId()) == null ? null
                                    : lineTotals.get(line.lineItemId()).huf())
                            : BigDecimal.ZERO;
                    return new DraftQuoteSnapshot.QuoteLineSnapshot(
                            line.lineItemId(), line.workId(), line.catalogItemId(), line.catalogItem(),
                            line.settlementMode(), line.requirements(), line.calculationPlan(),
                            line.calculationResult(), line.requiredQuantity(), line.procurementQuantity(),
                            line.customerChargeQuantity(), line.userOverrideQuantity(),
                            line.assumptionsApproved(), line.approvedFingerprint(), line.explanation(),
                            total, line.chargeable(), line.procurementPackageCount(), line.assumptions(),
                            line.procurementOutputReference(), line.procurementPackageCountReference(),
                            line.manualNetUnitPrice(), line.manualPriceReason(), line.userOverrideLine());
                })
                .toList();

        return new RepricingResult(new DraftQuoteSnapshot(
                snapshot.schemaVersion(), null, snapshot.workItems(), pricedLines), pricing);
    }

    public record RepricingResult(DraftQuoteSnapshot snapshot, CostCalculationResult costResult) {
    }
}
