package com.buildassist.service;

import com.buildassist.calculation.CalculationInput;
import com.buildassist.calculation.CalculationOperation;
import com.buildassist.calculation.CalculationPlan;
import com.buildassist.calculation.CalculationResult;
import com.buildassist.calculation.CalculationStatus;
import com.buildassist.calculation.CalculationStep;
import com.buildassist.calculation.CatalogItemType;
import com.buildassist.calculation.ChargeComponent;
import com.buildassist.calculation.DraftQuoteSnapshot;
import com.buildassist.calculation.InputSource;
import com.buildassist.calculation.Quantity;
import com.buildassist.calculation.QuantityUnit;
import com.buildassist.calculation.SettlementMode;
import com.buildassist.calculation.WorkRecognitionStatus;
import com.buildassist.dto.AiDtos.CoverageDecision;
import com.buildassist.dto.AiDtos.CoverageStatus;
import com.buildassist.dto.AiDtos.CoverageWorkItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuoteCoverageServiceTest {

    private QuoteCoverageService service;

    @BeforeEach
    void setUp() {
        service = new QuoteCoverageService(new ObjectMapper());
    }

    @Test
    void coverageUsesStableWorkAndLineIdsRatherThanNameOrQuantityMatching() {
        DraftQuoteSnapshot snapshot = snapshot(
                List.of(work("work-sockets", "Konnektor szerelése", 20),
                        work("work-switches", "Konnektor szerelése", 8)),
                List.of(line("line-sockets", "work-sockets", 20, CalculationStatus.VALID),
                        line("line-switches", "work-switches", 8, CalculationStatus.VALID)));

        List<CoverageWorkItem> coverage = service.evaluate(snapshot, List.of());

        assertEquals(2, coverage.size());
        assertEquals("line-sockets", coverage.get(0).linkedLineItemId());
        assertEquals(List.of("line-sockets"), coverage.get(0).linkedLineItemIds());
        assertEquals(new BigDecimal("20"), coverage.get(0).quantity());
        assertEquals("line-switches", coverage.get(1).linkedLineItemId());
        assertEquals(new BigDecimal("8"), coverage.get(1).quantity());
        assertEquals(CoverageStatus.INCLUDED, coverage.get(0).status());
        assertEquals(CoverageStatus.INCLUDED, coverage.get(1).status());
    }

    @Test
    void unmappedAndInvalidWorkRemainVisibleAndUnresolved() {
        DraftQuoteSnapshot snapshot = snapshot(
                List.of(
                        new DraftQuoteSnapshot.QuoteWorkSnapshot(
                                "work-unmapped", "Kábelhossz mérése", InputSource.CUSTOMER_EMAIL,
                                null, null, SettlementMode.MATERIAL_AND_LABOR,
                                WorkRecognitionStatus.UNMAPPED, null, "Kábelhossz mérése",
                                "Nincs megfelelő katalógustétel.", true, List.of(), List.of()),
                        work("work-invalid", "Festési felület", 40)),
                List.of(line("line-invalid", "work-invalid", 40, CalculationStatus.INVALID)));

        List<CoverageWorkItem> coverage = service.evaluate(snapshot, List.of());

        assertEquals(CoverageStatus.MISSING, coverage.get(0).status());
        assertEquals(CoverageStatus.NEEDS_REVIEW, coverage.get(1).status());
        assertEquals(CalculationStatus.INVALID, coverage.get(1).calculationStatus());
        var response = service.response(snapshot, List.of(), List.of(), List.of(), true, "fingerprint");
        assertEquals(2, response.unresolvedIssues().size());
        assertTrue(response.unresolvedIssues().get(1).contains("INVALID"));
    }

    @Test
    void decisionsCanOnlyLinkAValidLineDirectlyOwnedByTheWork() {
        DraftQuoteSnapshot snapshot = snapshot(
                List.of(work("work-a", "Konnektor", 20), work("work-b", "Kapcsoló", 8)),
                List.of(line("line-a", "work-a", 20, CalculationStatus.VALID),
                        line("line-b", "work-b", 8, CalculationStatus.VALID)));

        assertThrows(IllegalArgumentException.class, () -> service.setDecision(
                List.of(), "work-a", CoverageStatus.INCLUDED, "line-b", snapshot));
        List<CoverageDecision> decisions = service.setDecision(
                List.of(), "work-a", CoverageStatus.INCLUDED, "line-a", snapshot);
        List<CoverageWorkItem> view = service.evaluate(snapshot, decisions);

        assertEquals(CoverageStatus.INCLUDED, view.get(0).status());
        assertTrue(view.get(0).userConfirmed());
        assertEquals("line-a", view.get(0).linkedLineItemId());
    }

    @Test
    void flagsCombinedAndSeparateComponentDoubleChargeButAllowsSameCatalogAcrossWorks() {
        DraftQuoteSnapshot.QuoteLineSnapshot labor = line(
                "line-labor-separate", "work-a", 20, CalculationStatus.VALID);
        DraftQuoteSnapshot.QuoteLineSnapshot combined = withCombinedComponents(
                line("line-combined", "work-a", 20, CalculationStatus.VALID));
        DraftQuoteSnapshot.QuoteLineSnapshot otherWorkSameCatalog = line(
                "line-labor-other-work", "work-b", 8, CalculationStatus.VALID);
        DraftQuoteSnapshot quote = snapshot(
                List.of(work("work-a", "Konnektor szerelése", 20),
                        work("work-b", "Kapcsoló szerelése", 8)),
                List.of(combined, labor, otherWorkSameCatalog));

        assertEquals(java.util.Set.of("work-a"), service.duplicateChargeWorkIds(quote, java.util.Set.of()));
        assertEquals(CoverageStatus.NEEDS_REVIEW, service.evaluate(quote, List.of()).getFirst().status());
        assertTrue(service.duplicateChargeWorkIds(quote, java.util.Set.of("work-a")).isEmpty());
    }

    @Test
    void unrelatedSnapshotEditsDoNotInvalidateAnUnchangedLineApprovalFingerprint() {
        DraftQuoteSnapshot.QuoteWorkSnapshot firstWork = work("work-a", "Konnektor", 20);
        DraftQuoteSnapshot.QuoteLineSnapshot firstLine = line(
                "line-a", "work-a", 20, CalculationStatus.VALID);
        DraftQuoteSnapshot firstSnapshot = snapshot(List.of(firstWork), List.of(firstLine));
        DraftQuoteSnapshot expandedSnapshot = snapshot(
                List.of(firstWork, work("work-b", "Kapcsoló", 8)),
                List.of(firstLine, line("line-b", "work-b", 8, CalculationStatus.VALID)));

        assertEquals(service.lineItemFingerprint(firstSnapshot, firstLine),
                service.lineItemFingerprint(expandedSnapshot, firstLine));
    }

    private DraftQuoteSnapshot.QuoteLineSnapshot withCombinedComponents(
            DraftQuoteSnapshot.QuoteLineSnapshot original) {
        Quantity quantity = original.customerChargeQuantity();
        List<DraftQuoteSnapshot.RequirementSnapshot> requirements = List.of(
                new DraftQuoteSnapshot.RequirementSnapshot(
                        "req-combined-material", ChargeComponent.MATERIAL, quantity, quantity, quantity, null, null),
                original.requirements().getFirst());
        return new DraftQuoteSnapshot.QuoteLineSnapshot(
                original.lineItemId(), original.workId(), original.catalogItemId(),
                new DraftQuoteSnapshot.CatalogItemSnapshot(
                        "Kombinált szerelés", "db", CatalogItemType.COMBINED,
                        new BigDecimal("800"), null, null,
                        com.buildassist.calculation.CatalogCalculationConfig.empty()),
                original.settlementMode(), requirements, original.calculationPlan(),
                original.calculationResult(), original.requiredQuantity(), original.procurementQuantity(),
                original.customerChargeQuantity(), null, false, null, original.explanation(),
                original.netLineTotal(), true, null, List.of());
    }

    private DraftQuoteSnapshot snapshot(
            List<DraftQuoteSnapshot.QuoteWorkSnapshot> works,
            List<DraftQuoteSnapshot.QuoteLineSnapshot> lines) {
        return new DraftQuoteSnapshot(
                DraftQuoteSnapshot.CURRENT_SCHEMA_VERSION,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                works, lines);
    }

    private DraftQuoteSnapshot.QuoteWorkSnapshot work(String id, String description, int quantity) {
        return new DraftQuoteSnapshot.QuoteWorkSnapshot(
                id, description, InputSource.CUSTOMER_EMAIL,
                new Quantity(BigDecimal.valueOf(quantity), QuantityUnit.EACH), "db",
                SettlementMode.MATERIAL_AND_LABOR, WorkRecognitionStatus.MAPPED,
                100L, description, description, true, List.of(), List.of());
    }

    private DraftQuoteSnapshot.QuoteLineSnapshot line(
            String lineId, String workId, int quantity, CalculationStatus status) {
        Quantity measured = new Quantity(BigDecimal.valueOf(quantity), QuantityUnit.EACH);
        CalculationResult result = status == CalculationStatus.VALID
                ? new CalculationResult(status, measured, List.of(), List.of())
                : new CalculationResult(status, null, List.of(), List.of());
        List<DraftQuoteSnapshot.RequirementSnapshot> requirements =
                List.of(new DraftQuoteSnapshot.RequirementSnapshot(
                        "req-" + lineId, ChargeComponent.LABOR,
                        status == CalculationStatus.VALID ? measured : null,
                        null,
                        status == CalculationStatus.VALID ? measured : null,
                        new BigDecimal("100"), null));
        return new DraftQuoteSnapshot.QuoteLineSnapshot(
                lineId, workId, 100L,
                new DraftQuoteSnapshot.CatalogItemSnapshot(
                        "Konnektor szerelés", "db",
                        com.buildassist.calculation.CatalogItemType.LABOR,
                        new BigDecimal("500"), null, new BigDecimal("100"),
                        com.buildassist.calculation.CatalogCalculationConfig.empty()),
                SettlementMode.MATERIAL_AND_LABOR,
                requirements,
                new CalculationPlan(
                        List.of(new CalculationInput(
                                "count", BigDecimal.valueOf(quantity), "db", InputSource.CUSTOMER_EMAIL, true)),
                        List.of(new CalculationStep(
                                "result", CalculationOperation.DIRECT, List.of("count"), "db", null, null)),
                        "result", List.of()),
                result,
                status == CalculationStatus.VALID ? measured : null,
                null,
                status == CalculationStatus.VALID ? measured : null,
                null, false, null, "Direct customer request.",
                status == CalculationStatus.VALID ? BigDecimal.valueOf(quantity * 500L) : null,
                true, null, List.of());
    }
}
