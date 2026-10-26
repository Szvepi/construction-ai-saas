package com.buildassist.service;

import com.buildassist.dto.AiDtos.*;
import com.buildassist.model.CatalogItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuoteCoverageServiceTest {

    private QuoteCoverageService coverageService;

    @BeforeEach
    void setUp() {
        coverageService = new QuoteCoverageService(new ObjectMapper());
    }

    @Test
    void matchesDifferentNamesOnlyWhenAiProvidesAClearCatalogMatch() {
        RequestedWork demolition = new RequestedWork("work-1", "Régi beton bontása",
                "Kért bontási munka.", CoverageSource.EMAIL, null, null, 42L,
                "Meglévő betonburkolat elbontása", "DIRECT");
        DraftLineItem quoteLine = line("Meglévő betonburkolat elbontása", "line-1", 42L);

        CoverageWorkItem result = coverageService.evaluate(List.of(demolition), List.of(), List.of(quoteLine)).get(0);

        assertEquals(CoverageStatus.INCLUDED, result.status());
        assertEquals("line-1", result.linkedLineItemId());
        assertFalse(result.userConfirmed());
    }

    @Test
    void reportsMissingCandidateAndReviewsUncertainGeneralMatch() {
        RequestedWork missing = work("work-1", "Konténer rendelése", 42L);
        RequestedWork uncertain = work("work-2", "Járda térkövezése", null);
        List<DraftLineItem> quoteLines = List.of(line("Térkövezés", "line-1", null));

        List<CoverageWorkItem> result = coverageService.evaluate(
                List.of(missing, uncertain), List.of(), quoteLines);

        assertEquals(CoverageStatus.MISSING, result.get(0).status());
        assertEquals(CoverageStatus.NEEDS_REVIEW, result.get(1).status());
    }

    @Test
    void doesNotTreatGenericCatalogRowsAsProofOfCoverage() {
        RequestedWork request = new RequestedWork("work-1", "Konténer rendelése", "Kért munka.",
                CoverageSource.EMAIL, null, null, 12L, "Általános kivitelezési munka", "DIRECT");
        DraftLineItem genericQuoteLine = line("Általános kivitelezési munka", "line-1", 12L);

        CoverageWorkItem result = coverageService.evaluate(
                List.of(request), List.of(), List.of(genericQuoteLine)).get(0);

        assertEquals(CoverageStatus.NEEDS_REVIEW, result.status());
    }

    @Test
    void exactManualLineCanCoverWorkAndUserCanPersistExplicitDecisions() {
        RequestedWork work = work("work-1", "Sitt elszállítása", null);
        DraftLineItem line = line("Sitt elszállítása", "line-1", null);
        List<CoverageDecision> decision = coverageService.setDecision(
                List.of(), "work-1", CoverageStatus.INCLUDED, "line-1", List.of(line));

        CoverageWorkItem explicit = coverageService.evaluate(List.of(work), decision, List.of(line)).get(0);
        assertEquals(CoverageStatus.INCLUDED, explicit.status());
        assertTrue(explicit.userConfirmed());

        List<CoverageDecision> excluded = coverageService.setDecision(
                decision, "work-1", CoverageStatus.EXCLUDED, null, List.of(line));
        assertEquals(CoverageStatus.EXCLUDED,
                coverageService.evaluate(List.of(work), excluded, List.of(line)).get(0).status());

        List<CoverageDecision> deferred = coverageService.setDecision(
                excluded, "work-1", CoverageStatus.NEEDS_REVIEW, null, List.of(line));
        assertEquals(CoverageStatus.NEEDS_REVIEW,
                coverageService.evaluate(List.of(work), deferred, List.of(line)).get(0).status());
    }

    @Test
    void invalidatesCoverageWhenLinkedLineIsDeletedOrMateriallyChanged() {
        RequestedWork work = work("work-1", "Sitt elszállítása", null);
        DraftLineItem original = line("Sitt elszállítása", "line-1", null);
        List<CoverageDecision> decision = coverageService.setDecision(
                List.of(), "work-1", CoverageStatus.INCLUDED, "line-1", List.of(original));

        assertEquals(CoverageStatus.NEEDS_REVIEW,
                coverageService.evaluate(List.of(work), decision, List.of()).get(0).status());
        DraftLineItem changed = new DraftLineItem("Másik munka", "m3", BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.ZERO, "line-1", null);
        assertEquals(CoverageStatus.NEEDS_REVIEW,
                coverageService.evaluate(List.of(work), decision, List.of(changed)).get(0).status());
    }

    @Test
    void identifiesSourceAndKeepsOnlyReliableQuantitiesFromAiData() {
        CatalogItem catalog = new CatalogItem();
        catalog.setId(7L);
        catalog.setName("Járda térkövezése");
        catalog.setUnit("m2");
        AiExtractionResult extraction = new AiExtractionResult(
                null, List.of(), List.of(), List.of(),
                List.of(new AiRequestedWork("Járda térkövezése", "ADDITIONAL_INFO", 7L,
                        BigDecimal.valueOf(15), "m2", "Telefonos egyeztetés.")));

        RequestedWork result = coverageService.createRequestedWorks(extraction, List.of(catalog)).get(0);

        assertEquals(CoverageSource.ADDITIONAL_INFO, result.source());
        assertEquals(BigDecimal.valueOf(15), result.quantity());
        assertEquals("m2", result.unit());
        assertEquals(7L, result.suggestedCatalogItemId());
    }

    @Test
    void honorsTenPercentMaterialWasteWithoutTreatingSeparateAreaAsCovered() {
        CatalogItem pavingCatalog = new CatalogItem();
        pavingCatalog.setId(9L);
        pavingCatalog.setName("Térkő lerakás (munkadíj)");
        pavingCatalog.setUnit("m2");
        pavingCatalog.setCalculationStrategy(com.buildassist.model.CalculationStrategy.WASTE_PERCENTAGE_10);
        List<RequestedWork> requests = List.of(
                new RequestedWork("drive", "Kocsibeálló térkövezése", "Kért munka.",
                        CoverageSource.EMAIL, BigDecimal.valueOf(45), "m2", 9L, pavingCatalog.getName(),
                        pavingCatalog.getCalculationStrategy().name()),
                new RequestedWork("walkway", "Járda térkövezése", "Kiegészítő információ.",
                        CoverageSource.ADDITIONAL_INFO, BigDecimal.valueOf(15), "m2", 9L,
                        pavingCatalog.getName(), pavingCatalog.getCalculationStrategy().name()));
        DraftLineItem pavingLine = new DraftLineItem("Térkő lerakás (munkadíj)", "m2",
                new BigDecimal("49.50"), BigDecimal.ZERO, BigDecimal.ZERO, "line-9", 9L);

        List<CoverageWorkItem> result = coverageService.evaluate(requests, List.of(), List.of(pavingLine));

        assertEquals(CoverageStatus.INCLUDED, result.get(0).status());
        assertEquals(CoverageStatus.NEEDS_REVIEW, result.get(1).status());
    }

    @Test
    void acknowledgesWarningsAndKeepsAnalysisFreshnessInTheGate() {
        CoverageResponse fresh = coverageService.response(
                List.of(), List.of(), List.of(), List.of("A mennyiség ellenőrizendő."),
                List.of(), false);
        CoverageWarning warning = fresh.warnings().get(0);
        CoverageResponse acknowledged = coverageService.response(
                List.of(), List.of(), List.of(), List.of("A mennyiség ellenőrizendő."),
                List.of(warning.warningId()), true);

        assertFalse(fresh.unresolvedIssues().isEmpty());
        assertEquals(List.of(), acknowledged.unresolvedIssues());
        assertNotEquals(coverageService.analysisFingerprint("email", "első jegyzet"),
                coverageService.analysisFingerprint("email", "módosított jegyzet"));
    }

    private RequestedWork work(String id, String name, Long catalogId) {
        return new RequestedWork(id, name, "Ellenőrizd a lefedettséget.", CoverageSource.EMAIL,
                null, null, catalogId, null, null);
    }

    private DraftLineItem line(String name, String lineId, Long catalogId) {
        return new DraftLineItem(name, "m2", BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, lineId, catalogId);
    }
}
