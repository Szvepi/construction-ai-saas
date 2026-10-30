package com.buildassist.service;

import com.buildassist.calculation.CalculationInput;
import com.buildassist.calculation.CalculationOperation;
import com.buildassist.calculation.CalculationPlan;
import com.buildassist.calculation.CalculationResult;
import com.buildassist.calculation.CalculationStatus;
import com.buildassist.calculation.CalculationStep;
import com.buildassist.calculation.CatalogCalculationConfig;
import com.buildassist.calculation.CatalogItemType;
import com.buildassist.calculation.CatalogParameterConstraint;
import com.buildassist.calculation.ChargeComponent;
import com.buildassist.calculation.DraftQuoteSnapshot;
import com.buildassist.calculation.InputSource;
import com.buildassist.calculation.Quantity;
import com.buildassist.calculation.QuantityUnit;
import com.buildassist.calculation.SettlementMode;
import com.buildassist.dto.AiDtos.CalculationParameter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DraftQuoteLineEditorTest {

    @Test
    void supplyingAndApprovingMissingThicknessRecalculatesVolumeDeterministically() {
        DraftQuoteSnapshot.QuoteLineSnapshot line = missingThicknessLine();
        DraftQuoteLineEditor editor = new DraftQuoteLineEditor();
        List<CalculationParameter> parameters = List.of(
                new CalculationParameter("area", new BigDecimal("45"), "m2", "CUSTOMER_EMAIL", true),
                new CalculationParameter("thickness", new BigDecimal("0.15"), "m", "AI_ASSUMPTION", false));
        DraftQuoteSnapshot.QuoteLineSnapshot needsReview = editor.apply(line, parameters, false);
        assertEquals(CalculationStatus.NEEDS_REVIEW, needsReview.calculationResult().status());
        DraftQuoteSnapshot.QuoteLineSnapshot edited = editor.apply(needsReview, List.of(
                new CalculationParameter("area", new BigDecimal("45"), "m2", "CUSTOMER_EMAIL", true),
                new CalculationParameter("thickness", new BigDecimal("0.15"), "m", "AI_ASSUMPTION", true)
        ), true);

        assertEquals(CalculationStatus.VALID, edited.calculationResult().status());
        assertEquals(0, new BigDecimal("6.75").compareTo(edited.requiredQuantity().value()));
        assertEquals(0, new BigDecimal("6.75").compareTo(
                edited.requirements().getFirst().allowanceAdjustedQuantity().value()));
        assertEquals(true, edited.assumptionsApproved());
        assertEquals(64, edited.approvedFingerprint().length());
    }

    @Test
    void rejectedAssumptionIsNotUsedAndARealValueRecalculatesTheLine() {
        DraftQuoteSnapshot.QuoteLineSnapshot line = missingThicknessLine();
        DraftQuoteLineEditor editor = new DraftQuoteLineEditor();

        DraftQuoteSnapshot.QuoteLineSnapshot rejected = editor.apply(line, List.of(
                new CalculationParameter("area", new BigDecimal("45"), "m2", "CUSTOMER_EMAIL", true),
                new CalculationParameter("thickness", new BigDecimal("0.15"), "m",
                        "AI_ASSUMPTION", false, true)), false);
        assertEquals(CalculationStatus.NEEDS_INPUT, rejected.calculationResult().status());
        assertEquals(null, rejected.calculationPlan().inputs().get(1).value());
        assertEquals("USER_REJECTED_ASSUMPTION", rejected.calculationPlan().inputs().get(1).evidence());

        DraftQuoteSnapshot.QuoteLineSnapshot withActualValue = editor.apply(rejected, List.of(
                new CalculationParameter("area", new BigDecimal("45"), "m2", "CUSTOMER_EMAIL", true),
                new CalculationParameter("thickness", new BigDecimal("0.2"), "m",
                        "USER_OVERRIDE", true, false)), false);
        assertEquals(CalculationStatus.VALID, withActualValue.calculationResult().status());
        assertEquals(0, new BigDecimal("9.0").compareTo(withActualValue.requiredQuantity().value()));
        assertEquals(InputSource.USER_OVERRIDE, withActualValue.calculationPlan().inputs().get(1).source());
    }

    private DraftQuoteSnapshot.QuoteLineSnapshot missingThicknessLine() {
        CatalogCalculationConfig config = new CatalogCalculationConfig(
                Set.of(CalculationOperation.VOLUME), Map.of(),
                Map.of("thickness", new CatalogParameterConstraint(
                        "m", true, new BigDecimal("0.10"), new BigDecimal("0.30"))),
                null, null);
        CalculationPlan plan = new CalculationPlan(
                List.of(
                        new CalculationInput("area", new BigDecimal("45"), "m2",
                                InputSource.CUSTOMER_EMAIL, true, "45 m2"),
                        new CalculationInput("thickness", null, "m", InputSource.AI_ASSUMPTION, false)),
                List.of(new CalculationStep(
                        "volume", CalculationOperation.VOLUME,
                        List.of("area", "thickness"), "m3", null, null)),
                "volume", List.of("AI-javaslat, kivitelezői ellenőrzés szükséges: vastagság"));
        return new DraftQuoteSnapshot.QuoteLineSnapshot(
                "line-base", "work-base", 7L,
                new DraftQuoteSnapshot.CatalogItemSnapshot(
                        "Zúzottkő", "m3", CatalogItemType.MATERIAL,
                        new BigDecimal("5000"), new BigDecimal("3000"), null, config),
                SettlementMode.MATERIAL_AND_LABOR,
                List.of(new DraftQuoteSnapshot.RequirementSnapshot(
                        "req-material", ChargeComponent.MATERIAL, null, null, null,
                        new BigDecimal("3000"), null)),
                plan,
                new CalculationResult(CalculationStatus.NEEDS_INPUT, null, List.of(), List.of()),
                null, null, null, null, false, null,
                "Mennyiség × rétegvastagság", null, true, null,
                List.of("Rétegvastagság ellenőrzése szükséges"));
    }
}
