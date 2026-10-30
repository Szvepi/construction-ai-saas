package com.buildassist.service;

import com.buildassist.calculation.*;
import com.buildassist.dto.AiQuoteDtos;
import com.buildassist.model.CatalogItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiQuoteSnapshotBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private AiQuoteSnapshotBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new AiQuoteSnapshotBuilder(objectMapper);
    }

    @Test
    void calculatesPavingWorkDirectlyLinkedToEachWorkAndOnlyUsesConfiguredAllowance() {
        String email = "45 m² kocsibeálló, 15 cm alapréteg, 15 m hosszú beálló, "
                + "két oldalra szegély és külön 12 m² járda, ahol az alap már elkészült.";
        CatalogItem excavation = item(1L, "Földkiemelés", "m2", CatalogItemType.LABOR,
                "1200", null, "400", config(Set.of(CalculationOperation.DIRECT), Map.of(), null));
        CatalogItem gravel = item(2L, "Zúzottkő alapréteg", "m3", CatalogItemType.MATERIAL,
                "9000", "4500", null, config(
                        Set.of(CalculationOperation.VOLUME),
                        Map.of("thickness", new CatalogParameterDefault(
                                new BigDecimal("15"), "cm", InputSource.CONTRACTOR_PROFILE)),
                        null));
        CatalogItem curb = item(3L, "Szegélyezés", "m", CatalogItemType.LABOR,
                "1800", null, "700", config(Set.of(CalculationOperation.MULTIPLY), Map.of(), null));
        CatalogItem paver = item(4L, "Térkőanyag", "m2", CatalogItemType.MATERIAL,
                "8500", "5000", null, config(
                        Set.of(CalculationOperation.DIRECT), Map.of(), new BigDecimal("10")));
        CatalogItem laying = item(5L, "Térkőlerakási munkadíj", "m2", CatalogItemType.LABOR,
                "4500", null, "2100", config(Set.of(CalculationOperation.DIRECT), Map.of(), null));

        AiQuoteDtos.WorkProposal driveway = work(
                "driveway", "45 m² kocsibeálló", "45 m² kocsibeálló",
                new BigDecimal("45"), "m2", List.of(
                        directLine("excavation", 1L, "area", "45", "m2", "45 m² kocsibeálló", "m2"),
                        line("gravel", 2L,
                                List.of(input("area", "45", "m2", "CUSTOMER_EMAIL", "45 m² kocsibeálló")),
                                List.of(step("volume", "VOLUME", List.of("area", "thickness"), "m3")),
                                "volume", null, null),
                        line("curb", 3L,
                                List.of(input("length", "15", "m", "CUSTOMER_EMAIL", "15 m hosszú beálló"),
                                        input("sides", "2", "db", "CUSTOMER_EMAIL", "két oldalra szegély")),
                                List.of(step("total_curb", "MULTIPLY",
                                        List.of("length", "sides"), "m")),
                                "total_curb", null, null),
                        directLine("paver-material", 4L, "area", "45", "m2",
                                "45 m² kocsibeálló", "m2"),
                        directLine("paver-labor", 5L, "area", "45", "m2",
                                "45 m² kocsibeálló", "m2")));
        AiQuoteDtos.WorkProposal walkway = work(
                "walkway", "12 m² bejárati járda", "külön 12 m² járda, ahol az alap már elkészült",
                new BigDecimal("12"), "m2", List.of(
                        directLine("walkway-material", 4L, "area", "12", "m2",
                                "külön 12 m² járda", "m2"),
                        directLine("walkway-labor", 5L, "area", "12", "m2",
                                "külön 12 m² járda", "m2")));

        AiQuoteSnapshotBuilder.BuildResult result = builder.build(
                extraction(driveway, walkway), List.of(excavation, gravel, curb, paver, laying),
                email, null);
        DraftQuoteSnapshot snapshot = result.snapshot();

        assertEquals(CalculationStatus.VALID, result.calculationStatus());
        assertDecimal("45", line(snapshot, 1L, "driveway").requiredQuantity().value());
        assertDecimal("6.75", line(snapshot, 2L, "driveway").requiredQuantity().value());
        assertDecimal("30", line(snapshot, 3L, "driveway").requiredQuantity().value());
        DraftQuoteSnapshot.QuoteLineSnapshot drivewayPavers = line(snapshot, 4L, "driveway");
        assertDecimal("45", drivewayPavers.requiredQuantity().value());
        assertDecimal("49.5",
                drivewayPavers.requirements().getFirst().allowanceAdjustedQuantity().value());
        assertDecimal("49.5", drivewayPavers.procurementQuantity().value());
        assertDecimal("49.5", drivewayPavers.customerChargeQuantity().value());
        assertDecimal("45", line(snapshot, 5L, "driveway").requiredQuantity().value());
        assertDecimal("12", line(snapshot, 5L, "walkway").requiredQuantity().value());
        assertFalse(snapshot.lineItems().stream()
                .anyMatch(item -> item.workId().equals(workId(snapshot, "walkway"))
                        && item.catalogItemId().equals(2L)));
        assertNotEquals(line(snapshot, 5L, "driveway").lineItemId(),
                line(snapshot, 5L, "walkway").lineItemId());
        assertEquals(List.of("thickness"),
                snapshot.lineItems().stream()
                        .filter(item -> item.catalogItemId().equals(2L))
                        .flatMap(item -> item.calculationPlan().inputs().stream())
                        .filter(input -> input.source() == InputSource.CONTRACTOR_PROFILE)
                        .map(CalculationInput::id).distinct().toList());
    }

    @Test
    void calculatesPaintingSurfaceLayersAndProcurementPackagingSeparately() {
        String email = "5 × 4 m-es szoba, 2,7 m belmagasság, két réteg festés a falakon és a mennyezeten.";
        Map<String, CatalogParameterDefault> paintDefaults = Map.of(
                "coverage", new CatalogParameterDefault(
                        new BigDecimal("10"), "m2/l", InputSource.CATALOG_DEFAULT),
                "package_size", new CatalogParameterDefault(
                        new BigDecimal("2.5"), "l", InputSource.CATALOG_DEFAULT));
        Set<CalculationOperation> paintingOperations = EnumSet.of(
                CalculationOperation.AREA, CalculationOperation.ADD, CalculationOperation.PERIMETER,
                CalculationOperation.MULTIPLY, CalculationOperation.DIVIDE,
                CalculationOperation.COUNT, CalculationOperation.ROUND_UP_TO_MULTIPLE);
        CatalogItem labor = item(10L, "Festési munkadíj", "m2", CatalogItemType.LABOR,
                "3000", null, "1200", config(paintingOperations, Map.of(), null));
        CatalogItem paint = item(11L, "Festékanyag", "l", CatalogItemType.MATERIAL,
                "1400", "650", null, config(paintingOperations, paintDefaults, null));

        List<AiQuoteDtos.InputProposal> geometryInputs = List.of(
                input("length", "5", "m", "CUSTOMER_EMAIL", "5 × 4 m-es szoba"),
                input("width", "4", "m", "CUSTOMER_EMAIL", "5 × 4 m-es szoba"),
                input("height", "2.7", "m", "CUSTOMER_EMAIL", "2,7 m belmagasság"));
        List<AiQuoteDtos.StepProposal> areaSteps = List.of(
                step("perimeter", "PERIMETER", List.of("length", "width", "length", "width"), "m"),
                step("wall_area", "AREA", List.of("perimeter", "height"), "m2"),
                step("ceiling_area", "AREA", List.of("length", "width"), "m2"),
                step("total_area", "ADD", List.of("wall_area", "ceiling_area"), "m2"));
        AiQuoteDtos.PlanProposal laborPlan = plan(geometryInputs, areaSteps, "total_area");

        List<AiQuoteDtos.InputProposal> paintInputs = new ArrayList<>(geometryInputs);
        paintInputs.add(input("layers", "2", "db", "CUSTOMER_EMAIL", "két réteg festés"));
        List<AiQuoteDtos.StepProposal> paintSteps = new ArrayList<>(areaSteps);
        paintSteps.add(step("coated_area", "MULTIPLY", List.of("total_area", "layers"), "m2"));
        paintSteps.add(step("required_liters", "DIVIDE", List.of("coated_area", "coverage"), "l"));
        paintSteps.add(step("procurement_liters", "ROUND_UP_TO_MULTIPLE",
                List.of("required_liters", "package_size"), "l"));
        paintSteps.add(step("package_count_scalar", "DIVIDE",
                List.of("procurement_liters", "package_size"), "scalar"));
        paintSteps.add(step("package_count", "COUNT", List.of("package_count_scalar"), "db"));
        AiQuoteDtos.PlanProposal paintPlan = plan(paintInputs, paintSteps, "required_liters");

        AiQuoteDtos.WorkProposal work = work(
                "room", "5 × 4 méteres szoba festése",
                "5 × 4 m-es szoba, 2,7 m belmagasság", null, null, List.of(
                        line("labor", 10L, geometryInputs, areaSteps, "total_area", null, null),
                        line("paint", 11L, paintInputs, paintSteps, "required_liters",
                                "procurement_liters", "package_count")));

        AiQuoteSnapshotBuilder.BuildResult result = builder.build(
                extraction(work), List.of(labor, paint), email, null);
        DraftQuoteSnapshot.QuoteLineSnapshot laborLine = line(result.snapshot(), 10L, "room");
        DraftQuoteSnapshot.QuoteLineSnapshot paintLine = line(result.snapshot(), 11L, "room");

        assertEquals(CalculationStatus.VALID, result.calculationStatus());
        assertDecimal("68.6", laborLine.requiredQuantity().value());
        assertDecimal("48.6", laborLine.calculationResult().trace().stream()
                .filter(trace -> trace.stepId().equals("wall_area"))
                .findFirst().orElseThrow().result().value());
        assertDecimal("13.72", paintLine.requiredQuantity().value());
        assertDecimal("15", paintLine.procurementQuantity().value());
        assertDecimal("6", paintLine.procurementPackageCount().value());
        assertEquals(QuantityUnit.EACH, paintLine.procurementPackageCount().unit());
        assertDecimal("13.72", paintLine.customerChargeQuantity().value());
        assertTrue(paintLine.calculationPlan().inputs().stream()
                .anyMatch(input -> input.id().equals("coverage")
                        && input.source() == InputSource.CATALOG_DEFAULT
                        && input.value().compareTo(new BigDecimal("10")) == 0));

        List<AiQuoteDtos.InputProposal> layerInputs = new ArrayList<>(geometryInputs);
        layerInputs.add(input("layers", "2", "db", "CUSTOMER_EMAIL", "két réteg festés"));
        List<AiQuoteDtos.StepProposal> perLayerSteps = new ArrayList<>(areaSteps);
        perLayerSteps.add(step("labor_per_layer", "MULTIPLY",
                List.of("total_area", "layers"), "m2"));
        AiQuoteDtos.WorkProposal perLayerLabor = work(
                "room-per-layer", "Szoba festési munkadíja", "két réteg festés",
                null, null, List.of(line("per-layer-labor", 10L, layerInputs,
                        perLayerSteps, "labor_per_layer", null, null)));
        AiQuoteSnapshotBuilder.BuildResult perLayerResult = builder.build(
                extraction(perLayerLabor), List.of(labor), email, null);
        assertEquals(CalculationStatus.NEEDS_REVIEW, perLayerResult.calculationStatus());
        assertNull(perLayerResult.snapshot().lineItems().getFirst().requiredQuantity());
        assertNull(perLayerResult.snapshot().lineItems().getFirst().netLineTotal());
    }

    @Test
    void calculatesElectricalCountsWithoutInventingCableLengthAndCombinedPriceIsChargedOnce() {
        String email = "20 konnektor, 8 kapcsoló és 12 lámpakiállás szerelése. "
                + "Kábelhosszt majd a helyszínen mérünk.";
        List<CatalogItem> catalog = List.of(
                item(20L, "Konnektor anyag", "db", CatalogItemType.MATERIAL,
                        "2500", "1200", null, directConfig()),
                item(21L, "Konnektor munkadíj", "db", CatalogItemType.LABOR,
                        "3500", null, "1600", directConfig()),
                item(22L, "Kapcsoló anyag", "db", CatalogItemType.MATERIAL,
                        "1800", "800", null, directConfig()),
                item(23L, "Kapcsoló munkadíj", "db", CatalogItemType.LABOR,
                        "2200", null, "1000", directConfig()),
                item(24L, "Lámpakiállás anyag", "db", CatalogItemType.MATERIAL,
                        "3000", "1400", null, directConfig()),
                item(25L, "Lámpakiállás munkadíj", "db", CatalogItemType.LABOR,
                        "4000", null, "1900", directConfig()));
        List<AiQuoteDtos.WorkProposal> works = List.of(
                electricalWork("sockets", "Konnektor", "20 konnektor", 20,
                        List.of(directLine("socket-material", 20L, "count", "20", "db",
                                        "20 konnektor", "db"),
                                directLine("socket-labor", 21L, "count", "20", "db",
                                        "20 konnektor", "db"))),
                electricalWork("switches", "Kapcsoló", "8 kapcsoló", 8,
                        List.of(directLine("switch-material", 22L, "count", "8", "db",
                                        "8 kapcsoló", "db"),
                                directLine("switch-labor", 23L, "count", "8", "db",
                                        "8 kapcsoló", "db"))),
                electricalWork("lights", "Lámpakiállás", "12 lámpakiállás", 12,
                        List.of(directLine("light-material", 24L, "count", "12", "db",
                                        "12 lámpakiállás", "db"),
                                directLine("light-labor", 25L, "count", "12", "db",
                                        "12 lámpakiállás", "db"))));

        AiQuoteSnapshotBuilder.BuildResult result = builder.build(
                extraction(works.toArray(AiQuoteDtos.WorkProposal[]::new)), catalog, email, null);

        assertEquals(CalculationStatus.VALID, result.calculationStatus());
        assertEquals(6, result.snapshot().lineItems().size());
        assertDecimal("20", line(result.snapshot(), 21L, "sockets").requiredQuantity().value());
        assertDecimal("8", line(result.snapshot(), 23L, "switches").requiredQuantity().value());
        assertDecimal("12", line(result.snapshot(), 25L, "lights").requiredQuantity().value());
        assertFalse(result.snapshot().lineItems().stream()
                .anyMatch(item -> item.catalogItem().name().toLowerCase(Locale.ROOT).contains("kábel")));

        CatalogItem combined = item(26L, "Komplett konnektor szerelés", "db", CatalogItemType.COMBINED,
                "6000", "1300", "1800", directConfig());
        AiQuoteDtos.WorkProposal combinedWork = electricalWork(
                "combined", "Konnektor szerelés", "20 konnektor", 20,
                List.of(directLine("combined-socket", 26L, "count", "20", "db",
                        "20 konnektor", "db")));
        AiQuoteSnapshotBuilder.BuildResult combinedResult = builder.build(
                extraction(combinedWork), List.of(combined), email, null);
        DraftQuoteSnapshot.QuoteLineSnapshot combinedLine =
                combinedResult.snapshot().lineItems().getFirst();
        assertEquals(2, combinedLine.requirements().size());
        assertEquals(new BigDecimal("120000"), combinedLine.netLineTotal());
        assertEquals(new BigDecimal("120000"), combinedResult.netQuotePrice());
    }

    @Test
    void customerSuppliedMaterialIsNotChargedAtContractorsMaterialPrice() {
        String email = "20 konnektor felszerelése; az anyagot én biztosítom.";
        CatalogItem material = item(30L, "Konnektor anyag", "db", CatalogItemType.MATERIAL,
                "2500", "1200", null, directConfig());
        CatalogItem labor = item(31L, "Konnektor munkadíj", "db", CatalogItemType.LABOR,
                "3500", null, "1600", directConfig());
        AiQuoteDtos.WorkProposal work = new AiQuoteDtos.WorkProposal(
                "sockets", "20 konnektor felszerelése", true, "CUSTOMER_EMAIL",
                "20 konnektor", new BigDecimal("20"), "db", "CUSTOMER_SUPPLIED_MATERIAL",
                "az anyagot én biztosítom", "sockets",
                List.of(
                        directLine("socket-material", 30L, "count", "20", "db",
                                "20 konnektor", "db"),
                        directLine("socket-labor", 31L, "count", "20", "db",
                                "20 konnektor", "db")),
                List.of(), List.of());

        AiQuoteSnapshotBuilder.BuildResult result = builder.build(
                extraction(work), List.of(material, labor), email, null);

        DraftQuoteSnapshot.QuoteLineSnapshot materialLine = line(result.snapshot(), 30L, "sockets");
        DraftQuoteSnapshot.QuoteLineSnapshot laborLine = line(result.snapshot(), 31L, "sockets");
        assertFalse(materialLine.chargeable());
        assertEquals(BigDecimal.ZERO, materialLine.netLineTotal());
        assertEquals(new BigDecimal("70000"), laborLine.netLineTotal());
        assertEquals(new BigDecimal("70000"), result.netQuotePrice());
        assertDecimal("0", materialLine.requirements().getFirst().customerChargeQuantity().value());
    }

    @Test
    void laborOnlyModeKeepsMaterialAsNonChargeableAndMissingLaborCannotBeSilentlyFinalized() {
        String email = "20 konnektor felszerelése, az anyagot én biztosítom.";
        CatalogItem material = item(32L, "Konnektor anyag", "db", CatalogItemType.MATERIAL,
                "2500", "1200", null, directConfig());
        CatalogItem labor = item(33L, "Konnektor munkadíj", "db", CatalogItemType.LABOR,
                "3500", null, "1600", directConfig());
        AiQuoteDtos.WorkProposal laborOnly = new AiQuoteDtos.WorkProposal(
                "labor-only", "20 konnektor", true, "CUSTOMER_EMAIL",
                "20 konnektor", new BigDecimal("20"), "db", "LABOR_ONLY",
                "az anyagot én biztosítom", "Csak a szerelési munkadíj.",
                List.of(
                        directLine("labor-only-material", 32L, "count", "20", "db",
                                "20 konnektor", "db"),
                        directLine("labor-only-labor", 33L, "count", "20", "db",
                                "20 konnektor", "db")),
                List.of(), List.of());

        AiQuoteSnapshotBuilder.BuildResult result =
                builder.build(extraction(laborOnly), List.of(material, labor), email, null);
        assertEquals(CalculationStatus.VALID, result.calculationStatus());
        assertFalse(line(result.snapshot(), 32L, "Csak a szerelési munkadíj.").chargeable());
        assertDecimal("70000", result.netQuotePrice());

        AiQuoteDtos.WorkProposal partial = work(
                "partial", "20 konnektor", "20 konnektor", new BigDecimal("20"), "db",
                List.of(
                        directLine("partial-material", 32L, "count", "20", "db",
                                "20 konnektor", "db"),
                        line("missing-labor", null,
                                List.of(input("count", "20", "db", "CUSTOMER_EMAIL", "20 konnektor")),
                                List.of(step("labor-qty", "DIRECT", List.of("count"), "db")),
                                "labor-qty", null, null)));
        AiQuoteSnapshotBuilder.BuildResult partialResult =
                builder.build(extraction(partial), List.of(material, labor), email, null);
        assertEquals(CalculationStatus.NEEDS_REVIEW, partialResult.calculationStatus());
        assertEquals(1, partialResult.snapshot().lineItems().size());
        assertNull(partialResult.netQuotePrice());
        assertEquals(WorkRecognitionStatus.PARTIALLY_MAPPED,
                partialResult.snapshot().workItems().getFirst().recognitionStatus());
    }

    @Test
    void missingAndInvalidInputsNeverProduceAFinalQuantityOrPrice() {
        String email = "45 m² alapréteg készítése.";
        CatalogItem gravel = item(40L, "Zúzottkő alapréteg", "m3", CatalogItemType.MATERIAL,
                "9000", "4500", null, config(
                        Set.of(CalculationOperation.VOLUME), Map.of(), null));
        AiQuoteDtos.WorkProposal missingThickness = work(
                "missing", "45 m² zúzottkő alapréteg", "45 m² alapréteg",
                new BigDecimal("45"), "m2", List.of(line("missing-volume", 40L,
                        List.of(input("area", "45", "m2", "CUSTOMER_EMAIL", "45 m² alapréteg"),
                                input("thickness", null, "cm", "AI_ASSUMPTION", null)),
                        List.of(step("volume", "VOLUME", List.of("area", "thickness"), "m3")),
                        "volume", null, null)));

        AiQuoteSnapshotBuilder.BuildResult missingResult = builder.build(
                extraction(missingThickness), List.of(gravel), email, null);
        DraftQuoteSnapshot.QuoteLineSnapshot missingLine = missingResult.snapshot().lineItems().getFirst();
        assertEquals(CalculationStatus.NEEDS_INPUT, missingLine.calculationResult().status());
        assertNull(missingLine.requiredQuantity());
        assertNull(missingLine.netLineTotal());

        AiQuoteDtos.WorkProposal unknownUnit = work(
                "unknown-unit", "45 m² hibás egységű munkája", "45 m² alapréteg",
                new BigDecimal("45"), "m2", List.of(line("bad-unit", 40L,
                        List.of(input("area", "45", "fortnight", "CUSTOMER_EMAIL", "45 m² alapréteg")),
                        List.of(step("output", "DIRECT", List.of("area"), "m3")),
                        "output", null, null)));
        AiQuoteSnapshotBuilder.BuildResult invalidResult = builder.build(
                extraction(unknownUnit), List.of(gravel), email, null);
        DraftQuoteSnapshot.QuoteLineSnapshot invalidLine = invalidResult.snapshot().lineItems().getFirst();
        assertEquals(CalculationStatus.INVALID, invalidLine.calculationResult().status());
        assertNull(invalidLine.requiredQuantity());
        assertNull(invalidLine.netLineTotal());

        AiQuoteDtos.WorkProposal badOperation = work(
                "bad-operation", "Hibás képletű munkafázis", "45 m² alapréteg",
                new BigDecimal("45"), "m2", List.of(line("bad-op", 40L,
                        List.of(input("area", "45", "m2", "CUSTOMER_EMAIL", "45 m² alapréteg")),
                        List.of(step("evil", "EVALUATE", List.of("area"), "m3")),
                        "evil", null, null)));
        AiQuoteSnapshotBuilder.BuildResult operationResult = builder.build(
                extraction(badOperation), List.of(gravel), email, null);
        assertEquals(CalculationStatus.INVALID,
                operationResult.snapshot().lineItems().getFirst().calculationResult().status());
        assertNull(operationResult.snapshot().lineItems().getFirst().netLineTotal());
    }

    @Test
    void rejectsFalseSourceClaimsAndForeignCatalogIdsWithoutPricingThem() {
        String email = "45 m² alapréteg készítése.";
        CatalogItem gravel = item(50L, "Zúzottkő alapréteg", "m2", CatalogItemType.MATERIAL,
                "9000", "4500", null, directConfig());
        AiQuoteDtos.WorkProposal falseCitation = work(
                "false-source", "45 m² alapréteg", "45 m² alapréteg",
                new BigDecimal("45"), "m2", List.of(directLine("false-line", 50L,
                        "area", "45", "m2", "100 m²", "m2")));
        AiQuoteSnapshotBuilder.BuildResult falseResult = builder.build(
                extraction(falseCitation), List.of(gravel), email, null);
        assertEquals(CalculationStatus.INVALID,
                falseResult.snapshot().lineItems().getFirst().calculationResult().status());
        assertNull(falseResult.snapshot().lineItems().getFirst().netLineTotal());

        AiQuoteDtos.WorkProposal foreignCatalog = work(
                "foreign", "45 m² alapréteg", "45 m² alapréteg",
                new BigDecimal("45"), "m2", List.of(directLine("foreign-line", 999L,
                        "area", "45", "m2", "45 m² alapréteg", "m2")));
        AiQuoteSnapshotBuilder.BuildResult foreignResult = builder.build(
                extraction(foreignCatalog), List.of(gravel), email, null);
        assertTrue(foreignResult.snapshot().lineItems().isEmpty());
        assertEquals(WorkRecognitionStatus.UNMAPPED,
                foreignResult.snapshot().workItems().getFirst().recognitionStatus());
        assertEquals(1, foreignResult.snapshot().workItems().getFirst().unmappedLines().size());
        assertTrue(foreignResult.snapshot().workItems().getFirst().unmappedLines()
                .getFirst().reason().contains("nem érhető el"));
        assertNull(foreignResult.netQuotePrice());
    }

    @Test
    void ignoresAnAiInventedUnitPriceAndKeepsSameCatalogItemDistinctAcrossWorks() throws Exception {
        String email = "45 m² alapréteg és 12 m² kiegészítés.";
        CatalogItem material = item(60L, "Alapréteg", "m2", CatalogItemType.MATERIAL,
                "2500", "900", null, directConfig());
        String aiJson = """
                {
                  "client_name": "Teszt Ügyfél",
                  "work_items": [
                    {
                      "work_ref": "base",
                      "description": "45 m² alapréteg",
                      "explicitly_requested": true,
                      "source": "CUSTOMER_EMAIL",
                      "source_evidence": "45 m² alapréteg",
                      "requested_quantity": 45,
                      "requested_unit": "m2",
                      "settlement_mode": "MATERIAL_AND_LABOR",
                      "proposed_lines": [{
                        "line_ref": "base-line",
                        "catalog_item_id": 60,
                        "unit_price": 0.01,
                        "calculation_plan": {
                          "inputs": [{"id":"area","value":45,"unit":"m2",
                            "source":"CUSTOMER_EMAIL","evidence":"45 m² alapréteg"}],
                          "steps": [{"id":"result","operation":"DIRECT",
                            "operands":["area"],"output_unit":"m2"}],
                          "output_reference":"result"
                        }
                      }]
                    },
                    {
                      "work_ref": "extra",
                      "description": "12 m² kiegészítés",
                      "explicitly_requested": true,
                      "source": "CUSTOMER_EMAIL",
                      "source_evidence": "12 m² kiegészítés",
                      "requested_quantity": 12,
                      "requested_unit": "m2",
                      "settlement_mode": "MATERIAL_AND_LABOR",
                      "proposed_lines": [{
                        "line_ref": "extra-line",
                        "catalog_item_id": 60,
                        "unit_price": 0.02,
                        "calculation_plan": {
                          "inputs": [{"id":"area","value":12,"unit":"m2",
                            "source":"CUSTOMER_EMAIL","evidence":"12 m² kiegészítés"}],
                          "steps": [{"id":"result","operation":"DIRECT",
                            "operands":["area"],"output_unit":"m2"}],
                          "output_reference":"result"
                        }
                      }]
                    }
                  ],
                  "unmapped_requests": [],
                  "review_warnings": []
                }
                """;
        AiQuoteDtos.StructuredExtraction extraction =
                objectMapper.readValue(aiJson, AiQuoteDtos.StructuredExtraction.class);

        AiQuoteSnapshotBuilder.BuildResult result =
                builder.build(extraction, List.of(material), email, null);

        assertEquals(CalculationStatus.VALID, result.calculationStatus());
        assertEquals(2, result.snapshot().lineItems().size());
        assertEquals(new BigDecimal("142500"), result.netQuotePrice());
        assertDecimal("45", result.snapshot().lineItems().getFirst().requiredQuantity().value());
        assertDecimal("12", result.snapshot().lineItems().get(1).requiredQuantity().value());
        assertNotEquals(result.snapshot().lineItems().getFirst().workId(),
                result.snapshot().lineItems().get(1).workId());
        assertNotEquals(result.snapshot().lineItems().getFirst().lineItemId(),
                result.snapshot().lineItems().get(1).lineItemId());
    }

    private AiQuoteDtos.StructuredExtraction extraction(AiQuoteDtos.WorkProposal... works) {
        return new AiQuoteDtos.StructuredExtraction(
                "Teszt Ügyfél", List.of(works), List.of(), List.of());
    }

    private AiQuoteDtos.WorkProposal work(
            String ref,
            String description,
            String evidence,
            BigDecimal requestedQuantity,
            String requestedUnit,
            List<AiQuoteDtos.LineProposal> lines) {
        return new AiQuoteDtos.WorkProposal(
                ref, description, true, "CUSTOMER_EMAIL", evidence,
                requestedQuantity, requestedUnit, "MATERIAL_AND_LABOR", null,
                ref, lines, List.of(), List.of());
    }

    private AiQuoteDtos.WorkProposal electricalWork(
            String ref, String description, String evidence, int quantity,
            List<AiQuoteDtos.LineProposal> lines) {
        return work(ref, description, evidence, BigDecimal.valueOf(quantity), "db", lines);
    }

    private AiQuoteDtos.LineProposal directLine(
            String lineRef, Long catalogId, String inputId, String value, String inputUnit,
            String evidence, String outputUnit) {
        return line(lineRef, catalogId,
                List.of(input(inputId, value, inputUnit, "CUSTOMER_EMAIL", evidence)),
                List.of(step("direct", "DIRECT", List.of(inputId), outputUnit)),
                "direct", null, null);
    }

    private AiQuoteDtos.LineProposal line(
            String lineRef, Long catalogId,
            List<AiQuoteDtos.InputProposal> inputs,
            List<AiQuoteDtos.StepProposal> steps,
            String outputReference,
            String procurementReference,
            String packageCountReference) {
        return new AiQuoteDtos.LineProposal(
                lineRef, catalogId, plan(inputs, steps, outputReference),
                procurementReference, packageCountReference, null, List.of());
    }

    private AiQuoteDtos.PlanProposal plan(
            List<AiQuoteDtos.InputProposal> inputs,
            List<AiQuoteDtos.StepProposal> steps,
            String outputReference) {
        return new AiQuoteDtos.PlanProposal(inputs, steps, outputReference, List.of());
    }

    private AiQuoteDtos.InputProposal input(
            String id, String value, String unit, String source, String evidence) {
        return new AiQuoteDtos.InputProposal(
                id, value == null ? null : new BigDecimal(value), unit, source, evidence);
    }

    private AiQuoteDtos.StepProposal step(
            String id, String operation, List<String> operands, String outputUnit) {
        return new AiQuoteDtos.StepProposal(id, operation, operands, outputUnit, null, null);
    }

    private CatalogItem item(
            Long id, String name, String unit, CatalogItemType type,
            String sellingPrice, String procurementCost, String laborCost,
            CatalogCalculationConfig config) {
        CatalogItem item = new CatalogItem();
        item.setId(id);
        item.setName(name);
        item.setUnit(unit);
        item.setItemType(type);
        item.setNetSellingUnitPrice(new BigDecimal(sellingPrice));
        item.setNetProcurementUnitCost(procurementCost == null ? null : new BigDecimal(procurementCost));
        item.setEstimatedOwnLaborUnitCost(laborCost == null ? null : new BigDecimal(laborCost));
        if (config != null) {
            try {
                item.setCalculationConfigJson(objectMapper.writeValueAsString(config));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
        return item;
    }

    private CatalogCalculationConfig directConfig() {
        return config(Set.of(CalculationOperation.DIRECT), Map.of(), null);
    }

    private CatalogCalculationConfig config(
            Set<CalculationOperation> operations,
            Map<String, CatalogParameterDefault> defaults,
            BigDecimal allowance) {
        return new CatalogCalculationConfig(operations, defaults, Map.of(), allowance, null);
    }

    private DraftQuoteSnapshot.QuoteLineSnapshot line(
            DraftQuoteSnapshot snapshot, Long catalogItemId, String workRef) {
        String durableWorkId = workId(snapshot, workRef);
        return snapshot.lineItems().stream()
                .filter(item -> item.workId().equals(durableWorkId))
                .filter(item -> item.catalogItemId().equals(catalogItemId))
                .findFirst().orElseThrow();
    }

    private String workId(DraftQuoteSnapshot snapshot, String originalRequest) {
        return snapshot.workItems().stream()
                .filter(work -> originalRequest.equals(work.explanation()))
                .findFirst().orElseThrow().workId();
    }

    private void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
