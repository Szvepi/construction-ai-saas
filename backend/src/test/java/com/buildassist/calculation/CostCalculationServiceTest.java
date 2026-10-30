package com.buildassist.calculation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostCalculationServiceTest {

    private final CostCalculationService service = new CostCalculationService();

    @Test
    void keepsMaterialNeedAllowanceProcurementAndLaborChargeDistinct() {
        WorkItem work = work("paving", SettlementMode.MATERIAL_AND_LABOR, "45", "m2");
        MaterialRequirement material = new MaterialRequirement(
                "paving-stone", "paving",
                quantity("45", "m2"), quantity("49.5", "m2"), quantity("50", "m2"),
                quantity("49.5", "m2"), money("800"));
        LaborRequirement labor = new LaborRequirement(
                "laying", "paving", quantity("45", "m2"), money("600"));
        CostCalculationResult result = service.calculate(new CostCalculationRequest(
                List.of(work),
                List.of(material),
                List.of(labor),
                List.of(),
                List.of(
                        quoteLine("material-sale", "paving", SettlementMode.MATERIAL_AND_LABOR,
                                List.of("paving-stone"), "49.5", "m2", "1500"),
                        quoteLine("labor-sale", "paving", SettlementMode.MATERIAL_AND_LABOR,
                                List.of("laying"), "45", "m2", "1000"))));

        assertEquals(PricingStatus.VALID, result.pricingStatus());
        assertEquals(money("119250"), result.netQuotePrice());
        assertEquals(money("67000"), result.availableDirectCost());
        assertEquals(new NetMargin(new BigDecimal("52250")), result.estimatedMargin());
        assertEquals(CostCoverage.COMPLETE, result.costCoverage());
    }

    @Test
    void combinedLineCanCoverMaterialAndLaborExactlyOnce() {
        WorkItem work = work("install", SettlementMode.MATERIAL_AND_LABOR, "1", "db");
        CostCalculationResult result = service.calculate(new CostCalculationRequest(
                List.of(work),
                List.of(material("wire", "install", "1", "db", "2000")),
                List.of(labor("install-labor", "install", "1", "db", "1000")),
                List.of(),
                List.of(quoteLine("combined", "install", SettlementMode.MATERIAL_AND_LABOR,
                        List.of("wire", "install-labor"), "1", "db", "5000"))));

        assertEquals(PricingStatus.VALID, result.pricingStatus());
        assertEquals(money("5000"), result.netQuotePrice());
    }

    @Test
    void rejectsDoubleChargingWhenCombinedAndSeparateLineCoverSameRequirement() {
        WorkItem work = work("install", SettlementMode.MATERIAL_AND_LABOR, "1", "db");
        CostCalculationResult result = service.calculate(new CostCalculationRequest(
                List.of(work),
                List.of(material("wire", "install", "1", "db", "2000")),
                List.of(labor("install-labor", "install", "1", "db", "1000")),
                List.of(),
                List.of(
                        quoteLine("combined", "install", SettlementMode.MATERIAL_AND_LABOR,
                                List.of("wire", "install-labor"), "1", "db", "5000"),
                        quoteLine("material-again", "install", SettlementMode.MATERIAL_AND_LABOR,
                                List.of("wire"), "1", "db", "2000"))));

        assertEquals(PricingStatus.INVALID, result.pricingStatus());
        assertNull(result.netQuotePrice());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("DUPLICATE_CHARGE")));
    }

    @Test
    void laborOnlyQuoteDoesNotIncludeMaterialSalePrice() {
        WorkItem work = work("labor-only", SettlementMode.LABOR_ONLY, "45", "m2");
        MaterialRequirement material = material("paint", "labor-only", "45", "m2", "1000");
        LaborRequirement labor = labor("paint-work", "labor-only", "45", "m2", "500");
        CostCalculationResult result = service.calculate(new CostCalculationRequest(
                List.of(work),
                List.of(material),
                List.of(labor),
                List.of(),
                List.of(quoteLine("labor-line", "labor-only", SettlementMode.LABOR_ONLY,
                        List.of("paint-work"), "45", "m2", "1200"))));

        assertEquals(PricingStatus.VALID, result.pricingStatus());
        assertEquals(money("54000"), result.netQuotePrice());
        assertEquals(money("67500"), result.availableDirectCost());
        assertEquals(new NetMargin(new BigDecimal("-13500")), result.estimatedMargin());
    }

    @Test
    void customerSuppliedMaterialIsNotChargedAndHasNoContractorProcurementCost() {
        WorkItem work = work("customer-material", SettlementMode.CUSTOMER_SUPPLIED_MATERIAL, "20", "db");
        CostCalculationResult result = service.calculate(new CostCalculationRequest(
                List.of(work),
                List.of(material("socket", "customer-material", "20", "db", "900")),
                List.of(labor("socket-install", "customer-material", "20", "db", "300")),
                List.of(),
                List.of(quoteLine("install-only", "customer-material",
                        SettlementMode.CUSTOMER_SUPPLIED_MATERIAL,
                        List.of("socket-install"), "20", "db", "1000"))));

        assertEquals(PricingStatus.VALID, result.pricingStatus());
        assertEquals(money("20000"), result.netQuotePrice());
        assertEquals(money("6000"), result.availableDirectCost());
    }

    @Test
    void rejectsCustomerSuppliedMaterialChargeAndMismatchedWorkReferences() {
        WorkItem work = work("customer-material", SettlementMode.CUSTOMER_SUPPLIED_MATERIAL, "1", "db");
        CostCalculationResult suppliedMaterial = service.calculate(new CostCalculationRequest(
                List.of(work), List.of(material("socket", "customer-material", "1", "db", "5")),
                List.of(), List.of(),
                List.of(quoteLine("material-line", "customer-material",
                        SettlementMode.CUSTOMER_SUPPLIED_MATERIAL, List.of("socket"), "1", "db", "10"))));
        CostCalculationResult crossWork = service.calculate(new CostCalculationRequest(
                List.of(work("first", SettlementMode.MATERIAL_AND_LABOR, "1", "db"),
                        work("second", SettlementMode.MATERIAL_AND_LABOR, "1", "db")),
                List.of(material("material", "first", "1", "db", "5")),
                List.of(), List.of(),
                List.of(quoteLine("cross", "second", SettlementMode.MATERIAL_AND_LABOR,
                        List.of("material"), "1", "db", "10"))));

        assertEquals(PricingStatus.INVALID, suppliedMaterial.pricingStatus());
        assertNull(suppliedMaterial.netQuotePrice());
        assertEquals(PricingStatus.INVALID, crossWork.pricingStatus());
        assertTrue(crossWork.issues().stream().anyMatch(issue -> issue.code().equals("CROSS_WORK_REQUIREMENT")));
    }

    @Test
    void reportsPartialCostsInsteadOfClaimingFullProjectMargin() {
        WorkItem work = work("paint", SettlementMode.MATERIAL_AND_LABOR, "10", "m2");
        CostCalculationResult result = service.calculate(new CostCalculationRequest(
                List.of(work),
                List.of(material("paint-material", "paint", "10", "m2", null)),
                List.of(labor("paint-labor", "paint", "10", "m2", "200")),
                List.of(new OtherCost("travel", "paint", null)),
                List.of(quoteLine("paint-line", "paint", SettlementMode.MATERIAL_AND_LABOR,
                        List.of("paint-material", "paint-labor"), "10", "m2", "500"))));

        assertEquals(PricingStatus.VALID, result.pricingStatus());
        assertEquals(CostCoverage.PARTIAL, result.costCoverage());
        assertEquals(money("2000"), result.availableDirectCost());
        assertEquals(new NetMargin(new BigDecimal("3000")), result.estimatedMargin());
        assertEquals(List.of("paint-material", "travel"), result.missingCostIds());
    }

    @Test
    void allMissingCostsMakeMarginUnavailableAndMissingQuotePriceBlocksFinalPrice() {
        WorkItem work = work("labor", SettlementMode.LABOR_ONLY, "2", "h");
        CostCalculationResult noCostData = service.calculate(new CostCalculationRequest(
                List.of(work), List.of(), List.of(labor("labor-hours", "labor", "2", "h", null)),
                List.of(), List.of(quoteLine("labor-price", "labor", SettlementMode.LABOR_ONLY,
                        List.of("labor-hours"), "2", "h", "10000"))));
        CostCalculationResult noSellingPrice = service.calculate(new CostCalculationRequest(
                List.of(work), List.of(), List.of(labor("labor-hours", "labor", "2", "h", "3000")),
                List.of(), List.of(quoteLine("labor-price", "labor", SettlementMode.LABOR_ONLY,
                        List.of("labor-hours"), "2", "h", null))));

        assertEquals(CostCoverage.UNAVAILABLE, noCostData.costCoverage());
        assertNull(noCostData.estimatedMargin());
        assertEquals(PricingStatus.NEEDS_INPUT, noSellingPrice.pricingStatus());
        assertNull(noSellingPrice.netQuotePrice());
    }

    @Test
    void addsFixedTravelCostAndCustomerChargeSeparately() {
        WorkItem work = work("service", SettlementMode.LABOR_ONLY, "1", "db");
        CostCalculationResult result = service.calculate(new CostCalculationRequest(
                List.of(work), List.of(), List.of(),
                List.of(new OtherCost("travel", "service", money("4000"))),
                List.of(quoteLine("travel-charge", "service", SettlementMode.LABOR_ONLY,
                        List.of("travel"), "1", "db", "6500"))));

        assertEquals(money("6500"), result.netQuotePrice());
        assertEquals(money("4000"), result.availableDirectCost());
        assertEquals(new NetMargin(new BigDecimal("2500")), result.estimatedMargin());
    }

    @Test
    void roundsHufAmountsHalfUpToWholeForints() {
        WorkItem work = work("small-job", SettlementMode.LABOR_ONLY, "1", "h");
        CostCalculationResult result = service.calculate(new CostCalculationRequest(
                List.of(work), List.of(), List.of(labor("time", "small-job", "1", "h", "0.49")),
                List.of(),
                List.of(quoteLine("charge", "small-job", SettlementMode.LABOR_ONLY,
                        List.of("time"), "1", "h", "10.5"))));

        assertEquals(money("11"), result.netQuotePrice());
        assertEquals(money("0"), result.availableDirectCost());
    }

    private WorkItem work(String id, SettlementMode mode, String quantity, String unit) {
        return new WorkItem(id, "Test work", quantity(quantity, unit), mode);
    }

    private MaterialRequirement material(
            String id, String workId, String quantity, String unit, String procurementRate) {
        Quantity q = quantity(quantity, unit);
        return new MaterialRequirement(id, workId, q, q, q, q,
                procurementRate == null ? null : money(procurementRate));
    }

    private LaborRequirement labor(
            String id, String workId, String quantity, String unit, String ownCostRate) {
        return new LaborRequirement(id, workId, quantity(quantity, unit),
                ownCostRate == null ? null : money(ownCostRate));
    }

    private QuoteLineItem quoteLine(
            String id, String workId, SettlementMode mode, List<String> requirementIds,
            String quantity, String unit, String netRate) {
        return new QuoteLineItem(id, workId, mode, requirementIds, quantity(quantity, unit),
                netRate == null ? null : money(netRate));
    }

    private Quantity quantity(String value, String unit) {
        return new Quantity(new BigDecimal(value), QuantityUnit.fromCode(unit));
    }

    private NetMoney money(String value) {
        return NetMoney.of(new BigDecimal(value));
    }
}
