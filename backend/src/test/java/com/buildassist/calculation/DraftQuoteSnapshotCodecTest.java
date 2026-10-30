package com.buildassist.calculation;

import com.buildassist.service.DraftQuoteSnapshotCodec;
import com.buildassist.service.DraftQuoteSnapshotService;
import com.buildassist.model.EmailDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DraftQuoteSnapshotCodecTest {

    private final DraftQuoteSnapshotCodec codec = new DraftQuoteSnapshotCodec(new ObjectMapper());

    @Test
    void roundTripsStableIdsSnapshotPricesCalculationStatusAndUserDecisions() {
        CatalogCalculationConfig config = new CatalogCalculationConfig(
                java.util.Set.of(CalculationOperation.VOLUME, CalculationOperation.DIRECT),
                Map.of("thickness", new CatalogParameterDefault(
                        new BigDecimal("15"), "cm", InputSource.CONTRACTOR_PROFILE)),
                Map.of(), new BigDecimal("10"), new QuantityRoundingPolicy(2, java.math.RoundingMode.HALF_UP));
        DraftQuoteSnapshot snapshot = new DraftQuoteSnapshot(
                DraftQuoteSnapshot.CURRENT_SCHEMA_VERSION,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                List.of(
                        new DraftQuoteSnapshot.QuoteWorkSnapshot(
                                "work-a", "45 négyzetméter térkövezés", InputSource.CUSTOMER_EMAIL,
                                new Quantity(new BigDecimal("45"), QuantityUnit.SQUARE_METER),
                                "m2", SettlementMode.MATERIAL_AND_LABOR,
                                WorkRecognitionStatus.MAPPED, 41L,
                                "45 négyzetméter térkövezés", "Explicit customer request.", true,
                                List.of(), List.of()),
                        new DraftQuoteSnapshot.QuoteWorkSnapshot(
                                "work-b", "12 négyzetméter járda", InputSource.CUSTOMER_EMAIL,
                                new Quantity(new BigDecimal("12"), QuantityUnit.SQUARE_METER),
                                "m2", SettlementMode.MATERIAL_AND_LABOR,
                                WorkRecognitionStatus.MAPPED, 41L,
                                "12 négyzetméter járda", "Explicit customer request.", true,
                                List.of(), List.of())),
                List.of(
                        line("line-a", "work-a", 41L, config),
                        line("line-b", "work-b", 41L, config)));

        DraftQuoteSnapshot sealed = codec.seal(snapshot);
        DraftQuoteSnapshot restored = codec.decode(codec.encode(sealed));

        assertEquals(sealed, restored);
        assertEquals("work-a", restored.lineItems().getFirst().workId());
        assertEquals("line-a", restored.lineItems().getFirst().lineItemId());
        assertEquals("line-b", restored.lineItems().get(1).lineItemId());
        assertEquals(new BigDecimal("42"), restored.lineItems().getFirst().userOverrideQuantity().value());
        assertEquals(CalculationStatus.VALID,
                restored.lineItems().getFirst().calculationResult().status());
        assertEquals(new BigDecimal("1500"), restored.lineItems().getFirst().catalogItem().netSellingUnitPrice());
        assertFalse(restored.approvalIsCurrent(restored.lineItems().getFirst()));
    }

    @Test
    void missingSnapshotIsAbsentAndUnknownVersionsAreRejected() {
        assertNull(codec.decode(null));
        assertNull(codec.decode(" "));
        assertThrows(IllegalStateException.class, () ->
                codec.decode("{\"schemaVersion\":99,\"fingerprint\":\"x\",\"workItems\":[],\"lineItems\":[]}"));
        assertThrows(IllegalStateException.class, () -> codec.decode("{invalid"));
    }

    @Test
    void generatedWorkAndLineIdsAreDistinctStableIdentifiers() {
        QuoteIdGenerator generator = new QuoteIdGenerator();

        String workId = generator.newWorkId();
        String lineItemId = generator.newLineItemId();

        assertTrue(workId.startsWith("work-"));
        assertTrue(lineItemId.startsWith("line-"));
        assertNotEquals(workId, lineItemId);
    }

    @Test
    void incompleteCalculationCannotBeSealedWithAFinalQuantityOrPrice() {
        CalculationResult needsInput = new CalculationResult(
                CalculationStatus.NEEDS_INPUT, null,
                List.of(new CalculationIssue("NEEDS_INPUT", "Thickness is missing.", "thickness")),
                List.of());
        DraftQuoteSnapshot.QuoteLineSnapshot invalidLine = new DraftQuoteSnapshot.QuoteLineSnapshot(
                "line-incomplete", "work-incomplete", 9L,
                new DraftQuoteSnapshot.CatalogItemSnapshot(
                        "Material", "m2", CatalogItemType.MATERIAL,
                        new BigDecimal("100"), new BigDecimal("50"), null, null),
                SettlementMode.MATERIAL_AND_LABOR,
                List.of(new DraftQuoteSnapshot.RequirementSnapshot(
                        "req-incomplete", ChargeComponent.MATERIAL,
                        new Quantity(new BigDecimal("10"), QuantityUnit.SQUARE_METER),
                        new Quantity(new BigDecimal("10"), QuantityUnit.SQUARE_METER),
                        new Quantity(new BigDecimal("10"), QuantityUnit.SQUARE_METER),
                        new BigDecimal("50"), null)),
                null,
                needsInput,
                new Quantity(new BigDecimal("10"), QuantityUnit.SQUARE_METER),
                new Quantity(new BigDecimal("10"), QuantityUnit.SQUARE_METER),
                new Quantity(new BigDecimal("10"), QuantityUnit.SQUARE_METER),
                null, false, null, "Incorrectly retained quantity.", new BigDecimal("1000"));
        DraftQuoteSnapshot incomplete = new DraftQuoteSnapshot(
                DraftQuoteSnapshot.CURRENT_SCHEMA_VERSION, null,
                List.of(new DraftQuoteSnapshot.QuoteWorkSnapshot(
                        "work-incomplete", "10 m2", InputSource.CUSTOMER_EMAIL,
                        new Quantity(new BigDecimal("10"), QuantityUnit.SQUARE_METER), "m2",
                        SettlementMode.MATERIAL_AND_LABOR)),
                List.of(invalidLine));

        assertThrows(IllegalArgumentException.class, () -> codec.encode(codec.seal(incomplete)));
    }

    @Test
    void draftPersistenceUsesOneQuoteLineSource() {
        DraftQuoteSnapshotService service = new DraftQuoteSnapshotService(codec);
        DraftQuoteSnapshot snapshot = new DraftQuoteSnapshot(
                DraftQuoteSnapshot.CURRENT_SCHEMA_VERSION,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                List.of(), List.of());
        DraftQuoteSnapshot sealed = codec.seal(snapshot);
        EmailDraft draft = new EmailDraft();

        service.save(draft, sealed);

        assertEquals(sealed, service.load(draft));
        draft.setLineItemsJson("[]");
        assertThrows(IllegalStateException.class, () -> service.save(draft, sealed));
    }

    private DraftQuoteSnapshot.QuoteLineSnapshot line(
            String lineId, String workId, Long catalogId, CatalogCalculationConfig config) {
        CalculationPlan plan = new CalculationPlan(
                List.of(new CalculationInput(
                        "area", new BigDecimal("45"), "m2", InputSource.CUSTOMER_EMAIL, true,
                        "45 m² paving")),
                List.of(new CalculationStep("result", CalculationOperation.DIRECT,
                        List.of("area"), "m2", null, null)),
                "result", List.of());
        CalculationResult result = new CalculationResult(
                CalculationStatus.VALID,
                new Quantity(new BigDecimal("45"), QuantityUnit.SQUARE_METER),
                List.of(), List.of());
        return new DraftQuoteSnapshot.QuoteLineSnapshot(
                lineId, workId, catalogId,
                new DraftQuoteSnapshot.CatalogItemSnapshot(
                        "Paving stone", "m2", CatalogItemType.MATERIAL,
                        new BigDecimal("1500"), new BigDecimal("900"), null, config),
                SettlementMode.MATERIAL_AND_LABOR,
                List.of(new DraftQuoteSnapshot.RequirementSnapshot(
                        "material-" + workId, ChargeComponent.MATERIAL,
                        new Quantity(new BigDecimal("45"), QuantityUnit.SQUARE_METER),
                        new Quantity(new BigDecimal("49.5"), QuantityUnit.SQUARE_METER),
                        new Quantity(new BigDecimal("49.5"), QuantityUnit.SQUARE_METER),
                        new BigDecimal("900"), null)),
                plan, result,
                new Quantity(new BigDecimal("45"), QuantityUnit.SQUARE_METER),
                new Quantity(new BigDecimal("49.5"), QuantityUnit.SQUARE_METER),
                new Quantity(new BigDecimal("49.5"), QuantityUnit.SQUARE_METER),
                new Quantity(new BigDecimal("42"), QuantityUnit.SQUARE_METER),
                true, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "A kézi felülírás jóváhagyásra került.",
                new BigDecimal("63000"));
    }
}
