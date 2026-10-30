package com.buildassist.service;

import com.buildassist.calculation.*;
import com.buildassist.dto.AiQuoteDtos;
import com.buildassist.model.CatalogItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class AiQuoteSnapshotBuilder {

    private static final int MAX_WORK_ITEMS = 100;
    private static final int MAX_LINES_PER_WORK = 32;
    private static final int MAX_WARNINGS = 50;
    private static final int MAX_TEXT_LENGTH = 2_000;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9._:-]{0,79}");
    private static final Map<Integer, List<String>> HUNGARIAN_NUMBER_WORDS = Map.ofEntries(
            Map.entry(0, List.of("nulla")),
            Map.entry(1, List.of("egy")),
            Map.entry(2, List.of("két", "kettő", "ketto")),
            Map.entry(3, List.of("három", "harom")),
            Map.entry(4, List.of("négy", "negy")),
            Map.entry(5, List.of("öt", "ot")),
            Map.entry(6, List.of("hat")),
            Map.entry(7, List.of("hét", "het")),
            Map.entry(8, List.of("nyolc")),
            Map.entry(9, List.of("kilenc")),
            Map.entry(10, List.of("tíz", "tiz")),
            Map.entry(11, List.of("tizenegy")),
            Map.entry(12, List.of("tizenkettő", "tizenkét", "tizenkettő")),
            Map.entry(20, List.of("húsz", "husz")));

    private final DeterministicQuantityCalculator quantityCalculator;
    private final CostCalculationService costCalculationService;
    private final CatalogCalculationConfigValidator configValidator;
    private final ObjectMapper objectMapper;
    private final QuoteIdGenerator idGenerator;

    public AiQuoteSnapshotBuilder(ObjectMapper objectMapper) {
        this(new DeterministicQuantityCalculator(), new CostCalculationService(),
                new CatalogCalculationConfigValidator(), objectMapper, new QuoteIdGenerator());
    }

    AiQuoteSnapshotBuilder(
            DeterministicQuantityCalculator quantityCalculator,
            CostCalculationService costCalculationService,
            CatalogCalculationConfigValidator configValidator,
            ObjectMapper objectMapper,
            QuoteIdGenerator idGenerator) {
        this.quantityCalculator = Objects.requireNonNull(quantityCalculator, "quantityCalculator");
        this.costCalculationService = Objects.requireNonNull(costCalculationService, "costCalculationService");
        this.configValidator = Objects.requireNonNull(configValidator, "configValidator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    public BuildResult build(
            AiQuoteDtos.StructuredExtraction extraction,
            List<CatalogItem> tenantCatalog,
            String emailBody,
            String additionalNotes) {
        Objects.requireNonNull(extraction, "extraction");
        List<CatalogItem> safeCatalog = tenantCatalog == null ? List.of() : List.copyOf(tenantCatalog);
        Map<Long, CatalogItem> catalogById = new HashMap<>();
        Map<Long, CatalogCalculationConfig> configById = new HashMap<>();
        for (CatalogItem item : safeCatalog) {
            if (item == null || item.getId() == null) {
                throw new IllegalStateException("The tenant catalog contains an item without a persisted ID.");
            }
            catalogById.put(item.getId(), item);
            configById.put(item.getId(), readConfig(item));
        }

        if (extraction.workItems().size() + extraction.unmappedRequests().size() > MAX_WORK_ITEMS) {
            throw new IllegalArgumentException("The AI result exceeds the supported work-item limit.");
        }

        List<String> warnings = new ArrayList<>(
                boundedStrings(extraction.reviewWarnings(), MAX_WARNINGS));
        List<WorkBuild> workBuilds = new ArrayList<>();
        Set<String> workRefs = new HashSet<>();
        Set<String> lineRefs = new HashSet<>();

        for (AiQuoteDtos.WorkProposal proposal : extraction.workItems()) {
            requireSafeId(proposal.workRef(), "work_ref");
            if (!workRefs.add(proposal.workRef())) {
                throw new IllegalArgumentException("The AI result contains duplicate work_ref identifiers.");
            }
            if (proposal.proposedLines().size() > MAX_LINES_PER_WORK) {
                throw new IllegalArgumentException("A work item exceeds the supported proposed-line limit.");
            }
            WorkBuild work = createWork(proposal, emailBody, additionalNotes, warnings);
            workBuilds.add(work);
            for (AiQuoteDtos.LineProposal lineProposal : proposal.proposedLines()) {
                requireSafeId(lineProposal.lineRef(), "line_ref");
                if (!lineRefs.add(lineProposal.lineRef())) {
                    throw new IllegalArgumentException("The AI result contains duplicate line_ref identifiers.");
                }
                work.proposedLineCount++;
                if (lineProposal.catalogItemId() == null
                        || !catalogById.containsKey(lineProposal.catalogItemId())) {
                    work.unmappedLineCount++;
                    work.unmappedLines.add(new DraftQuoteSnapshot.UnmappedLineSnapshot(
                            new com.buildassist.calculation.QuoteIdGenerator().newLineItemId(),
                            safeText(lineProposal.explanation()),
                            lineProposal.catalogItemId() == null
                                    ? "Az AI nem javasolt katalógustételt."
                                    : "A javasolt katalógustétel nem érhető el ehhez a vállalkozáshoz.",
                            boundedStrings(lineProposal.assumptions(), 16),
                            retainUnmappedPlan(lineProposal.calculationPlan(), emailBody, additionalNotes)));
                    if (lineProposal.catalogItemId() != null) {
                        warnings.add("Egy javasolt katalógustétel nem érhető el ehhez a vállalkozáshoz; "
                                + "az ügyféligény nem lett beárazva.");
                    }
                    continue;
                }
                work.mappedLineCount++;
                if (!work.multipleCatalogItems) {
                    if (work.suggestedCatalogItemId == null) {
                        work.suggestedCatalogItemId = lineProposal.catalogItemId();
                    } else if (!work.suggestedCatalogItemId.equals(lineProposal.catalogItemId())) {
                        work.suggestedCatalogItemId = null;
                        work.multipleCatalogItems = true;
                    }
                }
                CatalogItem catalogItem = catalogById.get(lineProposal.catalogItemId());
                CatalogCalculationConfig config = configById.get(lineProposal.catalogItemId());
                LineBuild line = buildLine(
                        work, lineProposal, catalogItem, config, proposal.missingParameters(),
                        emailBody, additionalNotes);
            }
        }

        for (AiQuoteDtos.UnmappedRequest proposal : extraction.unmappedRequests()) {
            requireSafeId(proposal.workRef(), "unmapped work_ref");
            if (!workRefs.add(proposal.workRef())) {
                throw new IllegalArgumentException("The AI result reuses a work_ref for an unmapped request.");
            }
            SourceResolution source = resolveSource(
                    proposal.source(), proposal.sourceEvidence(), emailBody, additionalNotes);
            List<String> workWarnings = new ArrayList<>();
            if (source.issue() != null) {
                workWarnings.add(source.issue());
            }
            workBuilds.add(new WorkBuild(
                    idGenerator.newWorkId(),
                    requireDescription(proposal.request()),
                    source.source(),
                    null,
                    null,
                    SettlementMode.MATERIAL_AND_LABOR,
                    null,
                    source.evidence(),
                    safeText(proposal.reason()),
                    false,
                    List.of(),
                    List.of(),
                    workWarnings));
        }

        return calculateAndBuild(workBuilds, warnings);
    }

    private BuildResult calculateAndBuild(List<WorkBuild> workBuilds, List<String> warnings) {
        List<WorkItem> domainWorks = new ArrayList<>();
        List<MaterialRequirement> materialRequirements = new ArrayList<>();
        List<LaborRequirement> laborRequirements = new ArrayList<>();
        List<OtherCost> otherCosts = new ArrayList<>();
        List<QuoteLineItem> quoteLines = new ArrayList<>();
        List<DraftQuoteSnapshot.QuoteWorkSnapshot> workSnapshots = new ArrayList<>();
        List<DraftQuoteSnapshot.QuoteLineSnapshot> lineSnapshots = new ArrayList<>();

        for (WorkBuild work : workBuilds) {
            WorkRecognitionStatus status = recognitionStatus(work);
            workSnapshots.add(new DraftQuoteSnapshot.QuoteWorkSnapshot(
                    work.workId,
                    work.description,
                    work.source,
                    work.requestedQuantity,
                    work.requestedUnit,
                    work.settlementMode,
                    status,
                    work.suggestedCatalogItemId,
                    work.sourceEvidence,
                    work.explanation,
                    work.explicitlyRequested,
                    work.assumptions,
                    work.missingParameters,
                    work.unmappedLines));
            domainWorks.add(new WorkItem(
                    work.workId, work.description, work.requestedQuantity, work.settlementMode));
            for (LineBuild line : work.lineBuilds) {
                lineSnapshots.add(line.snapshot());
                if (line.calculation().status() != CalculationStatus.VALID) {
                    continue;
                }
                materialRequirements.addAll(line.materialRequirements());
                laborRequirements.addAll(line.laborRequirements());
                otherCosts.addAll(line.otherCosts());
                if (line.customerQuoteLine() != null) {
                    quoteLines.add(line.customerQuoteLine());
                }
            }
        }

        CostCalculationResult pricing = costCalculationService.calculate(new CostCalculationRequest(
                domainWorks, materialRequirements, laborRequirements, otherCosts, quoteLines));
        Map<String, NetMoney> lineTotals = pricing.netLineTotals();
        List<DraftQuoteSnapshot.QuoteLineSnapshot> pricedLineSnapshots = lineSnapshots.stream()
                .map(line -> {
                    if (line.calculationResult().status() != CalculationStatus.VALID) {
                        return line;
                    }
                    if (!line.chargeable()) {
                        return copyWithTotal(line, BigDecimal.ZERO);
                    }
                    NetMoney total = lineTotals.get(line.lineItemId());
                    return copyWithTotal(line, total == null ? null : total.huf());
                })
                .toList();

        DraftQuoteSnapshot snapshot = new DraftQuoteSnapshot(
                DraftQuoteSnapshot.CURRENT_SCHEMA_VERSION, null, workSnapshots, pricedLineSnapshots);
        CalculationStatus status = overallStatus(snapshot, pricing);
        List<String> unmapped = workSnapshots.stream()
                .filter(work -> work.recognitionStatus() == WorkRecognitionStatus.UNMAPPED
                        || work.recognitionStatus() == WorkRecognitionStatus.PARTIALLY_MAPPED)
                .map(DraftQuoteSnapshot.QuoteWorkSnapshot::originalRequest)
                .toList();
        BigDecimal netQuotePrice = status == CalculationStatus.VALID && pricing.netQuotePrice() != null
                ? pricing.netQuotePrice().huf() : null;
        return new BuildResult(snapshot, boundedStrings(warnings, MAX_WARNINGS), unmapped,
                status, netQuotePrice, pricing);
    }

    private LineBuild buildLine(
            WorkBuild work,
            AiQuoteDtos.LineProposal proposal,
            CatalogItem catalogItem,
            CatalogCalculationConfig config,
            List<AiQuoteDtos.MissingParameter> workMissingParameters,
            String emailBody,
            String additionalNotes) {
        String lineItemId = idGenerator.newLineItemId();
        List<ChargeComponent> components = components(catalogItem.getItemType());
        List<String> assumptions = boundedStrings(proposal.assumptions(), 32);
        List<String> reviewReasons = new ArrayList<>();
        reviewReasons.addAll(work.reviewReasons);
        if (catalogItem.getItemType() == CatalogItemType.LABOR
                && dependsOnMultipleCoats(proposal.calculationPlan())) {
            reviewReasons.add("A munkadíj kalkulációja rétegszámtól függ; az egységár rétegenkénti "
                    + "elszámolási alapja nincs a katalógusban konfigurálva.");
        }
        assumptions.forEach(assumption ->
                reviewReasons.add("AI-javaslat, kivitelezői ellenőrzés szükséges: " + assumption));

        boolean chargeable = components.stream().noneMatch(component ->
                component == ChargeComponent.MATERIAL
                        && work.settlementMode != SettlementMode.MATERIAL_AND_LABOR);
        if (catalogItem.getItemType() == CatalogItemType.COMBINED
                && work.settlementMode != SettlementMode.MATERIAL_AND_LABOR) {
            chargeable = false;
            reviewReasons.add("A kombinált katalógustétel anyag- és munkadíja nem választható szét.");
        }
        if (catalogItem.getItemType() == CatalogItemType.COMBINED
                && config.materialAllowancePercent() != null
                && config.materialAllowancePercent().signum() > 0) {
            reviewReasons.add("A kombinált tétel anyagráhagyása nem számlázható külön a munkadíjtól.");
        }

        PlanPreparation prepared = preparePlan(
                proposal.calculationPlan(), config, workMissingParameters,
                emailBody, additionalNotes, reviewReasons);
        CalculationPlan plan = prepared.plan();
        CalculationResult calculation = prepared.result();
        if (calculation == null) {
            try {
                if (catalogItem.getItemType() == CatalogItemType.MATERIAL
                        && work.settlementMode == SettlementMode.MATERIAL_AND_LABOR
                        && config.materialAllowancePercent() != null
                        && config.materialAllowancePercent().signum() > 0) {
                    plan = appendMaterialAllowance(plan, config, catalogItem.getUnit());
                }
                calculation = quantityCalculator.calculate(plan);
            } catch (IllegalArgumentException exception) {
                calculation = invalidResult(
                        "INVALID_ALLOWANCE_PLAN",
                        "A katalógusban konfigurált anyagráhagyás nem építhető be a kalkulációba.",
                        proposal.lineRef());
            }
        }

        if (calculation.status() == CalculationStatus.VALID) {
            try {
                QuantityUnit catalogUnit = QuantityUnit.fromCode(catalogItem.getUnit());
                Quantity required = calculation.quantity().convertTo(catalogUnit);
                calculation = new CalculationResult(
                        CalculationStatus.VALID, required, calculation.issues(), calculation.trace());
            } catch (IllegalArgumentException exception) {
                calculation = invalidResult("CATALOG_UNIT_MISMATCH",
                        "A számított mennyiség mértékegysége nem kompatibilis a katalógustétellel.",
                        proposal.lineRef());
            }
        }

        Quantity requiredQuantity = calculation.status() == CalculationStatus.VALID
                ? calculation.quantity() : null;
        Quantity allowanceQuantity = requiredQuantity;
        Quantity procurementQuantity = null;
        Quantity packageCount = null;
        List<DraftQuoteSnapshot.RequirementSnapshot> requirementSnapshots = new ArrayList<>();
        List<MaterialRequirement> materials = new ArrayList<>();
        List<LaborRequirement> labor = new ArrayList<>();
        List<OtherCost> other = new ArrayList<>();
        QuoteLineItem customerQuoteLine = null;
        Quantity customerChargeQuantity = null;
        BigDecimal lineTotal = null;

        if (calculation.status() == CalculationStatus.VALID) {
            try {
                if (components.contains(ChargeComponent.MATERIAL)
                        && work.settlementMode == SettlementMode.MATERIAL_AND_LABOR) {
                    allowanceQuantity = materialAllowanceFromTrace(requiredQuantity, calculation, config);
                }
                if (components.contains(ChargeComponent.MATERIAL)) {
                    if (work.settlementMode == SettlementMode.CUSTOMER_SUPPLIED_MATERIAL) {
                        procurementQuantity = zero(requiredQuantity.unit());
                    } else if (proposal.procurementOutputReference() != null) {
                        CalculationResult procurementResult = calculateOutput(plan, proposal.procurementOutputReference());
                        if (procurementResult.status() != CalculationStatus.VALID) {
                            calculation = procurementResult;
                            throw new CalculationFailure("A beszerzési mennyiség nem számítható ki.");
                        }
                        procurementQuantity = procurementResult.quantity().convertTo(requiredQuantity.unit());
                    } else {
                        procurementQuantity = allowanceQuantity;
                    }
                    if (work.settlementMode != SettlementMode.CUSTOMER_SUPPLIED_MATERIAL
                            && procurementQuantity.value().compareTo(allowanceQuantity.value()) < 0) {
                        calculation = invalidResult("PROCUREMENT_BELOW_REQUIRED",
                                "A beszerzési mennyiség kisebb a ráhagyással növelt anyagszükségletnél.",
                                proposal.procurementOutputReference());
                        throw new CalculationFailure("A beszerzési mennyiség kisebb a szükségesnél.");
                    }
                    if (proposal.procurementPackageCountReference() != null) {
                        CalculationResult packageResult =
                                calculateOutput(plan, proposal.procurementPackageCountReference());
                        if (packageResult.status() != CalculationStatus.VALID
                                || packageResult.quantity().unit() != QuantityUnit.EACH
                                || packageResult.quantity().value().stripTrailingZeros().scale() > 0) {
                            calculation = invalidResult("INVALID_PACKAGE_COUNT",
                                    "A beszerzési kiszerelések darabszáma érvénytelen.",
                                    proposal.procurementPackageCountReference());
                            throw new CalculationFailure("A beszerzési kiszerelések darabszáma érvénytelen.");
                        }
                        packageCount = packageResult.quantity();
                    }
                }

                if (calculation.status() == CalculationStatus.VALID) {
                    for (ChargeComponent component : components) {
                        String requirementId = requirementId(lineItemId, component);
                        Quantity chargedQuantity = chargeable
                                ? (component == ChargeComponent.MATERIAL ? allowanceQuantity : requiredQuantity)
                                : zero(requiredQuantity.unit());
                        switch (component) {
                            case MATERIAL -> {
                                Quantity procurement = Objects.requireNonNull(procurementQuantity);
                                requirementSnapshots.add(new DraftQuoteSnapshot.RequirementSnapshot(
                                        requirementId, component, requiredQuantity, allowanceQuantity,
                                        procurement, chargedQuantity,
                                        catalogItem.getNetProcurementUnitCost(), null));
                                materials.add(new MaterialRequirement(
                                        requirementId, work.workId, requiredQuantity, allowanceQuantity,
                                        procurement, chargedQuantity,
                                        catalogItem.getNetProcurementUnitCost() == null ? null
                                                : NetMoney.of(catalogItem.getNetProcurementUnitCost())));
                                customerChargeQuantity = chargedQuantity;
                            }
                            case LABOR -> {
                                requirementSnapshots.add(new DraftQuoteSnapshot.RequirementSnapshot(
                                        requirementId, component, requiredQuantity, null, chargedQuantity,
                                        catalogItem.getEstimatedOwnLaborUnitCost(), null));
                                labor.add(new LaborRequirement(
                                        requirementId, work.workId, chargedQuantity,
                                        catalogItem.getEstimatedOwnLaborUnitCost() == null ? null
                                                : NetMoney.of(catalogItem.getEstimatedOwnLaborUnitCost())));
                                customerChargeQuantity = requiredQuantity;
                            }
                            case OTHER -> {
                                requirementSnapshots.add(new DraftQuoteSnapshot.RequirementSnapshot(
                                        requirementId, component, requiredQuantity, null, requiredQuantity,
                                        null, null));
                                other.add(new OtherCost(requirementId, work.workId, null));
                                customerChargeQuantity = requiredQuantity;
                            }
                        }
                    }
                    if (chargeable) {
                        customerQuoteLine = new QuoteLineItem(
                                lineItemId,
                                work.workId,
                                work.settlementMode,
                                requirementSnapshots.stream()
                                        .map(DraftQuoteSnapshot.RequirementSnapshot::requirementId)
                                        .toList(),
                                Objects.requireNonNull(customerChargeQuantity),
                                NetMoney.of(catalogItem.getNetSellingUnitPrice()));
                    } else {
                        lineTotal = BigDecimal.ZERO;
                    }
                }
            } catch (CalculationFailure | IllegalArgumentException exception) {
                if (calculation.status() == CalculationStatus.VALID) {
                    calculation = invalidResult("INVALID_REQUIREMENT",
                            "Az ajánlati követelmény vagy mennyiségi kapcsolat érvénytelen: "
                                    + safeMessage(exception),
                            proposal.lineRef());
                }
                requirementSnapshots.clear();
                materials.clear();
                labor.clear();
                other.clear();
                customerQuoteLine = null;
                requiredQuantity = null;
                allowanceQuantity = null;
                procurementQuantity = null;
                packageCount = null;
                customerChargeQuantity = null;
                lineTotal = null;
            }
        }

        if (calculation.status() != CalculationStatus.VALID) {
            requirementSnapshots.clear();
            for (ChargeComponent component : components) {
                requirementSnapshots.add(new DraftQuoteSnapshot.RequirementSnapshot(
                        requirementId(lineItemId, component), component,
                        null, null, null, null,
                        component == ChargeComponent.MATERIAL ? catalogItem.getNetProcurementUnitCost()
                                : component == ChargeComponent.LABOR
                                ? catalogItem.getEstimatedOwnLaborUnitCost() : null,
                        null));
            }
            requiredQuantity = null;
            allowanceQuantity = null;
            procurementQuantity = null;
            packageCount = null;
            customerChargeQuantity = null;
            lineTotal = null;
        }

        DraftQuoteSnapshot.CatalogItemSnapshot catalogSnapshot = new DraftQuoteSnapshot.CatalogItemSnapshot(
                catalogItem.getName(), catalogItem.getUnit(), catalogItem.getItemType(),
                catalogItem.getNetSellingUnitPrice(), catalogItem.getNetProcurementUnitCost(),
                catalogItem.getEstimatedOwnLaborUnitCost(), config);
        DraftQuoteSnapshot.QuoteLineSnapshot snapshot = new DraftQuoteSnapshot.QuoteLineSnapshot(
                lineItemId,
                work.workId,
                catalogItem.getId(),
                catalogSnapshot,
                work.settlementMode,
                requirementSnapshots,
                plan,
                calculation,
                requiredQuantity,
                procurementQuantity,
                customerChargeQuantity,
                null,
                false,
                null,
                safeText(proposal.explanation()),
                lineTotal,
                chargeable,
                packageCount,
                assumptions,
                proposal.procurementOutputReference(),
                proposal.procurementPackageCountReference());
        LineBuild lineBuild = new LineBuild(snapshot, calculation, materials, labor, other, customerQuoteLine);
        work.lineBuilds.add(lineBuild);
        return lineBuild;
    }

    private PlanPreparation preparePlan(
            AiQuoteDtos.PlanProposal proposal,
            CatalogCalculationConfig config,
            List<AiQuoteDtos.MissingParameter> workMissingParameters,
            String emailBody,
            String additionalNotes,
            List<String> externalReviewReasons) {
        if (proposal == null) {
            return new PlanPreparation(null,
                    invalidResult("PLAN_REQUIRED", "A katalógustételhez nem érkezett számítási terv.", null));
        }

        List<CalculationInput> inputs = new ArrayList<>();
        Set<String> inputIds = new HashSet<>();
        List<String> reviewReasons = new ArrayList<>(boundedStrings(proposal.reviewReasons(), 32));
        reviewReasons.addAll(externalReviewReasons);
        boolean invalidClaim = false;

        for (AiQuoteDtos.InputProposal input : proposal.inputs()) {
            if (input == null) {
                invalidClaim = true;
                continue;
            }
            if (input.id() != null) {
                inputIds.add(input.id());
            }
            InputSource claimedSource = parseSource(input.source());
            if (claimedSource == null) {
                invalidClaim = true;
                inputs.add(new CalculationInput(input.id(), null, input.unit(),
                        InputSource.AI_ASSUMPTION, false, null));
                reviewReasons.add("A bemenet eredetét az AI nem adta meg érvényesen.");
                continue;
            }
            CatalogParameterDefault configuredDefault = config.defaultParameters().get(input.id());
            if (claimedSource == InputSource.CATALOG_DEFAULT
                    || claimedSource == InputSource.CONTRACTOR_PROFILE
                    || (claimedSource == InputSource.AI_ASSUMPTION && configuredDefault != null)
                    || (input.value() == null && configuredDefault != null)) {
                if (configuredDefault == null) {
                    inputs.add(new CalculationInput(
                            input.id(), null, configuredUnit(config, input.id(), input.unit()),
                            InputSource.AI_ASSUMPTION, false, null));
                    reviewReasons.add("A kért kivitelezői alapérték nincs konfigurálva: " + input.id() + ".");
                } else {
                    if (input.value() != null && input.value().compareTo(configuredDefault.value()) != 0) {
                        reviewReasons.add("Az AI által javasolt értéktől eltérő, ténylegesen konfigurált alapérték "
                                + "került alkalmazásra: " + input.id() + ".");
                    }
                    inputs.add(new CalculationInput(input.id(), configuredDefault.value(),
                            configuredDefault.unit(), configuredDefault.source(), true, null));
                }
                continue;
            }
            if (claimedSource == InputSource.USER_OVERRIDE) {
                invalidClaim = true;
                inputs.add(new CalculationInput(input.id(), null, input.unit(),
                        InputSource.AI_ASSUMPTION, false, null));
                reviewReasons.add("Az AI nem jogosult felhasználói felülírást állítani.");
                continue;
            }
            if (claimedSource == InputSource.CUSTOMER_EMAIL
                    || claimedSource == InputSource.CUSTOMER_SUPPLEMENT) {
                if (input.value() != null && !verifiedNumericEvidence(
                        claimedSource, input.evidence(), input.value(), input.unit(),
                        emailBody, additionalNotes)) {
                    invalidClaim = true;
                    inputs.add(new CalculationInput(input.id(), null, input.unit(),
                            InputSource.AI_ASSUMPTION, false, null));
                    reviewReasons.add("A bemenet ügyfélszövegbeli forrása vagy számszerű bizonyítéka nem igazolható.");
                    continue;
                }
                inputs.add(new CalculationInput(input.id(), input.value(), input.unit(),
                        claimedSource, true, safeText(input.evidence())));
                continue;
            }
            if (claimedSource == InputSource.AI_ASSUMPTION) {
                inputs.add(new CalculationInput(input.id(), input.value(), input.unit(),
                        InputSource.AI_ASSUMPTION, false, null));
                continue;
            }
            invalidClaim = true;
            inputs.add(new CalculationInput(input.id(), null, input.unit(),
                    InputSource.AI_ASSUMPTION, false, null));
        }

        for (Map.Entry<String, CatalogParameterDefault> entry : config.defaultParameters().entrySet()) {
            if (inputIds.add(entry.getKey())) {
                CatalogParameterDefault value = entry.getValue();
                inputs.add(new CalculationInput(
                        entry.getKey(), value.value(), value.unit(), value.source(), true, null));
            }
        }
        for (Map.Entry<String, CatalogParameterConstraint> entry : config.parameterConstraints().entrySet()) {
            CatalogParameterConstraint constraint = entry.getValue();
            if (constraint.required() && !config.defaultParameters().containsKey(entry.getKey())
                    && inputIds.add(entry.getKey())) {
                inputs.add(new CalculationInput(entry.getKey(), null, constraint.unit(),
                        InputSource.AI_ASSUMPTION, false, null));
            }
        }
        for (AiQuoteDtos.MissingParameter missing : workMissingParameters) {
            if (missing == null || missing.id() == null || !SAFE_ID.matcher(missing.id()).matches()) {
                reviewReasons.add("A munkához tartozó hiányzó paraméter azonosítója érvénytelen.");
                continue;
            }
            if (config.defaultParameters().containsKey(missing.id())) {
                continue;
            }
            if (inputIds.add(missing.id())) {
                inputs.add(new CalculationInput(missing.id(), null,
                        configuredUnit(config, missing.id(), missing.unit()),
                        InputSource.AI_ASSUMPTION, false, null));
            }
            if (missing.reason() != null && !missing.reason().isBlank()) {
                reviewReasons.add("Hiányzó paraméter: " + safeText(missing.reason()));
            }
        }

        List<CalculationStep> steps = new ArrayList<>();
        Set<CalculationOperation> allowed = config.allowedCalculations().isEmpty()
                ? Set.of(CalculationOperation.DIRECT) : config.allowedCalculations();
        boolean invalidOperation = false;
        for (AiQuoteDtos.StepProposal step : proposal.steps()) {
            if (step == null) {
                invalidOperation = true;
                continue;
            }
            CalculationOperation operation = parseOperation(step.operation());
            if (operation == null || !allowed.contains(operation)) {
                invalidOperation = true;
                reviewReasons.add("Nem engedélyezett vagy ismeretlen AI-művelet: "
                        + Objects.toString(step.operation(), "(hiányzó)") + ".");
            }
            RoundingMode roundingMode = parseRoundingMode(step.roundingMode());
            if (step.roundingMode() != null && roundingMode == null) {
                invalidOperation = true;
                reviewReasons.add("A kerekítési mód nem támogatott.");
            }
            if (operation == CalculationOperation.ROUND) {
                QuantityRoundingPolicy policy = config.roundingPolicy();
                if (policy == null || !Objects.equals(step.scale(), policy.scale())
                        || roundingMode != policy.mode()) {
                    invalidOperation = true;
                    reviewReasons.add("A ROUND művelet nem egyezik a katalógus kerekítési beállításával.");
                }
            }
            steps.add(new CalculationStep(
                    step.id(), operation, step.operands(), step.outputUnit(), step.scale(), roundingMode));
        }

        validateParameterValues(inputs, config, reviewReasons);
        CalculationPlan plan = new CalculationPlan(inputs, steps, proposal.outputReference(), reviewReasons);
        if (invalidClaim || invalidOperation) {
            return new PlanPreparation(plan, invalidResult(
                    invalidClaim ? "UNVERIFIED_PROVENANCE" : "OPERATION_NOT_ALLOWED",
                    String.join(" ", reviewReasons), proposal.outputReference()));
        }
        return new PlanPreparation(plan, null);
    }

    private void validateParameterValues(
            List<CalculationInput> inputs,
            CatalogCalculationConfig config,
            List<String> reviewReasons) {
        for (CalculationInput input : inputs) {
            CatalogParameterConstraint constraint = config.parameterConstraints().get(input.id());
            if (constraint == null || input.value() == null || input.unit() == null) {
                continue;
            }
            try {
                Quantity value = new Quantity(input.value(), QuantityUnit.fromCode(input.unit()))
                        .convertTo(QuantityUnit.fromCode(constraint.unit()));
                if (constraint.minimum() != null && value.value().compareTo(constraint.minimum()) < 0
                        || constraint.maximum() != null && value.value().compareTo(constraint.maximum()) > 0) {
                    reviewReasons.add("A bemenet kívül esik a katalógusban megadott tartományon: "
                            + input.id() + ".");
                }
            } catch (IllegalArgumentException exception) {
                reviewReasons.add("A bemenet mértékegysége nem kompatibilis a katalógusparaméterrel: "
                        + input.id() + ".");
            }
        }
    }

    private CalculationPlan appendMaterialAllowance(
            CalculationPlan plan, CatalogCalculationConfig config, String outputUnit) {
        String inputId = "miraMaterialAllowancePercent";
        String stepId = "miraMaterialAllowance";
        boolean collision = plan.inputs().stream().anyMatch(input -> inputId.equals(input.id()))
                || plan.steps().stream().anyMatch(step -> inputId.equals(step.id()) || stepId.equals(step.id()));
        if (collision) {
            throw new IllegalArgumentException("The AI plan uses a reserved calculation identifier.");
        }
        List<CalculationInput> inputs = new ArrayList<>(plan.inputs());
        inputs.add(new CalculationInput(
                inputId, config.materialAllowancePercent(), "%", InputSource.CATALOG_DEFAULT, true));
        List<CalculationStep> steps = new ArrayList<>(plan.steps());
        steps.add(new CalculationStep(
                stepId, CalculationOperation.PERCENT_ADJUSTMENT,
                List.of(plan.outputReference(), inputId), outputUnit, null, null));
        return new CalculationPlan(inputs, steps, plan.outputReference(), plan.reviewReasons());
    }

    private boolean dependsOnMultipleCoats(AiQuoteDtos.PlanProposal plan) {
        if (plan == null) {
            return false;
        }
        Map<String, Set<String>> sourceInputs = new HashMap<>();
        Set<String> layerInputIds = new HashSet<>();
        for (AiQuoteDtos.InputProposal input : plan.inputs()) {
            if (input == null || input.id() == null) {
                continue;
            }
            sourceInputs.put(input.id(), Set.of(input.id()));
            String name = normalizeHungarian(input.id());
            if ((name.contains("layer") || name.contains("coat") || name.contains("reteg"))
                    && input.value() != null && input.value().compareTo(BigDecimal.ONE) > 0) {
                layerInputIds.add(input.id());
            }
        }
        for (AiQuoteDtos.StepProposal step : plan.steps()) {
            if (step == null || step.id() == null || step.operands() == null) {
                continue;
            }
            Set<String> dependencies = new HashSet<>();
            for (String operand : step.operands()) {
                dependencies.addAll(sourceInputs.getOrDefault(operand, Set.of()));
            }
            sourceInputs.put(step.id(), Set.copyOf(dependencies));
        }
        Set<String> outputInputs = sourceInputs.getOrDefault(plan.outputReference(), Set.of());
        return layerInputIds.stream().anyMatch(outputInputs::contains);
    }

    private Quantity materialAllowanceFromTrace(
            Quantity required, CalculationResult calculation, CatalogCalculationConfig config) {
        BigDecimal allowance = config.materialAllowancePercent();
        if (allowance == null || allowance.signum() == 0) {
            return required;
        }
        return calculation.trace().stream()
                .filter(entry -> entry.stepId().equals("miraMaterialAllowance"))
                .map(CalculationTraceEntry::result)
                .findFirst()
                .orElseThrow(() -> new CalculationFailure(
                        "The configured material allowance is missing from the calculation trace."))
                .convertTo(required.unit());
    }

    private CalculationResult calculateOutput(CalculationPlan plan, String outputReference) {
        return quantityCalculator.calculate(new CalculationPlan(
                plan.inputs(), plan.steps(), outputReference, plan.reviewReasons()));
    }

    private CatalogCalculationConfig readConfig(CatalogItem item) {
        String json = item.getCalculationConfigJson();
        if (json == null || json.isBlank()) {
            return CatalogCalculationConfig.empty();
        }
        try {
            CatalogCalculationConfig config = objectMapper.readValue(json, CatalogCalculationConfig.class);
            List<String> errors = configValidator.validate(config);
            if (!errors.isEmpty()) {
                throw new IllegalStateException("Stored catalog calculation configuration is invalid: "
                        + String.join(" ", errors));
            }
            return config;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored catalog calculation configuration is invalid.", exception);
        }
    }

    private WorkBuild createWork(
            AiQuoteDtos.WorkProposal proposal,
            String emailBody,
            String additionalNotes,
            List<String> warnings) {
        String description = requireDescription(proposal.description());
        SourceResolution source = resolveSource(
                proposal.source(), proposal.sourceEvidence(), emailBody, additionalNotes);
        List<String> workReviewReasons = new ArrayList<>();
        if (source.issue() != null) {
            workReviewReasons.add(source.issue());
            warnings.add(source.issue());
        }

        boolean explicitlyRequested = Boolean.TRUE.equals(proposal.explicitlyRequested());
        if (!explicitlyRequested) {
            workReviewReasons.add("Az AI ezt a munkát következtetésként javasolta, nem közvetlen ügyfélkérésként.");
        }
        if (source.source() == InputSource.AI_ASSUMPTION) {
            workReviewReasons.add("A munka ügyfélszövegbeli eredete nem hitelesített.");
        }

        Quantity requestedQuantity = null;
        String requestedUnit = safeText(proposal.requestedUnit());
        if (proposal.requestedQuantity() != null) {
            try {
                if (requestedUnit == null) {
                    throw new IllegalArgumentException("A kért mennyiség egysége hiányzik.");
                }
                Quantity candidate = new Quantity(
                        proposal.requestedQuantity(), QuantityUnit.fromCode(requestedUnit));
                if (candidate.value().signum() < 0) {
                    throw new IllegalArgumentException("A kért mennyiség nem lehet negatív.");
                }
                if ((source.source() == InputSource.CUSTOMER_EMAIL
                        || source.source() == InputSource.CUSTOMER_SUPPLEMENT)
                        && verifiedNumericEvidence(source.source(), source.evidence(), candidate.value(),
                        requestedUnit, emailBody, additionalNotes)) {
                    requestedQuantity = candidate;
                } else if (source.source() != InputSource.AI_ASSUMPTION) {
                    workReviewReasons.add("A kért mennyiség ügyfélszövegbeli forrása nem igazolható.");
                    warnings.add("Egy AI által megadott munkamennyiség forrása nem igazolható; "
                            + "az adatot nem tekintjük ügyfél által közölt mennyiségnek.");
                } else {
                    workReviewReasons.add("A kért mennyiség AI-feltételezés, ezért nem használható tényként.");
                }
            } catch (IllegalArgumentException exception) {
                workReviewReasons.add("A felismert munkamennyiség egysége vagy értéke érvénytelen.");
                warnings.add("Egy felismert munkamennyiség egysége vagy értéke érvénytelen.");
            }
        }

        SettlementResolution settlement = resolveSettlement(
                proposal.settlementMode(), proposal.settlementEvidence(), emailBody, additionalNotes);
        if (settlement.issue() != null) {
            workReviewReasons.add(settlement.issue());
            warnings.add(settlement.issue());
        }
        List<String> assumptions = boundedStrings(proposal.assumptions(), 32);
        List<String> missing = proposal.missingParameters().stream()
                .filter(Objects::nonNull)
                .map(this::formatMissingParameter)
                .filter(Objects::nonNull)
                .toList();
        assumptions.forEach(assumption ->
                workReviewReasons.add("AI-feltételezés ellenőrzendő: " + assumption));

        return new WorkBuild(idGenerator.newWorkId(), description, source.source(), requestedQuantity,
                requestedUnit, settlement.mode(), null,
                source.evidence(), safeText(proposal.explanation()), explicitlyRequested,
                assumptions, missing, workReviewReasons);
    }

    private CalculationPlan retainUnmappedPlan(
            AiQuoteDtos.PlanProposal proposal, String emailBody, String additionalNotes) {
        if (proposal == null) {
            return null;
        }
        List<CalculationInput> inputs = new ArrayList<>();
        List<String> reviewReasons = new ArrayList<>(boundedStrings(proposal.reviewReasons(), 32));
        for (AiQuoteDtos.InputProposal input : proposal.inputs()) {
            if (input == null || input.id() == null || !SAFE_ID.matcher(input.id()).matches()
                    || input.unit() == null || input.unit().isBlank()) {
                return null;
            }
            InputSource source = parseSource(input.source());
            if (source == InputSource.CUSTOMER_EMAIL || source == InputSource.CUSTOMER_SUPPLEMENT) {
                String evidenceSource = source == InputSource.CUSTOMER_EMAIL ? emailBody : additionalNotes;
                if (input.value() == null || safeText(input.evidence()) == null
                        || evidenceSource == null || !evidenceSource.contains(input.evidence())
                        || !verifiedNumericEvidence(source, input.evidence(), input.value(), input.unit(),
                        emailBody, additionalNotes)) {
                    inputs.add(new CalculationInput(
                            input.id(), null, input.unit(), InputSource.AI_ASSUMPTION, false, null));
                    reviewReasons.add("A hozzárendeléshez szükséges ügyféladat nem igazolható.");
                } else {
                    inputs.add(new CalculationInput(
                            input.id(), input.value(), input.unit(), source, true, safeText(input.evidence())));
                }
            } else if (source == InputSource.AI_ASSUMPTION) {
                inputs.add(new CalculationInput(input.id(), input.value(), input.unit(),
                        InputSource.AI_ASSUMPTION, false, null));
            } else {
                inputs.add(new CalculationInput(input.id(), null, input.unit(),
                        InputSource.AI_ASSUMPTION, false, null));
                reviewReasons.add("A katalógus- vagy kivitelezői értéket a szerver konfigurációja határozza meg.");
            }
        }
        List<CalculationStep> steps = new ArrayList<>();
        for (AiQuoteDtos.StepProposal step : proposal.steps()) {
            if (step == null || step.id() == null || !SAFE_ID.matcher(step.id()).matches()
                    || step.operands() == null || step.operands().isEmpty()) {
                return null;
            }
            CalculationOperation operation = parseOperation(step.operation());
            RoundingMode roundingMode = parseRoundingMode(step.roundingMode());
            if (operation == null || (step.roundingMode() != null && roundingMode == null)) {
                return null;
            }
            steps.add(new CalculationStep(
                    step.id(), operation, step.operands(), step.outputUnit(), step.scale(), roundingMode));
        }
        CalculationPlan plan = new CalculationPlan(inputs, steps, proposal.outputReference(), reviewReasons);
        return new CalculationPlanValidator().validate(plan).isEmpty() ? plan : null;
    }

    private SourceResolution resolveSource(
            String claimedSource, String evidence, String emailBody, String additionalNotes) {
        InputSource source = parseSource(claimedSource);
        if (source == InputSource.CUSTOMER_EMAIL || source == InputSource.CUSTOMER_SUPPLEMENT) {
            String quoted = safeText(evidence);
            String sourceText = source == InputSource.CUSTOMER_EMAIL ? emailBody : additionalNotes;
            if (quoted != null && sourceText != null && sourceText.contains(quoted)) {
                return new SourceResolution(source, quoted, null);
            }
            return new SourceResolution(InputSource.AI_ASSUMPTION, null,
                    "Az AI által hivatkozott ügyfélszövegbeli forrás nem található meg pontosan.");
        }
        if (source == InputSource.AI_ASSUMPTION) {
            return new SourceResolution(source, null, null);
        }
        return new SourceResolution(InputSource.AI_ASSUMPTION, null,
                "Az AI nem állíthat katalógus-, kivitelezői vagy felhasználói forrásból származó adatot.");
    }

    private SettlementResolution resolveSettlement(
            String modeValue, String evidence, String emailBody, String additionalNotes) {
        if (modeValue == null || modeValue.isBlank()) {
            return new SettlementResolution(SettlementMode.MATERIAL_AND_LABOR, null);
        }
        try {
            SettlementMode mode = SettlementMode.valueOf(modeValue.trim().toUpperCase(Locale.ROOT));
            if (mode == SettlementMode.MATERIAL_AND_LABOR) {
                return new SettlementResolution(mode, null);
            }
            String quoted = safeText(evidence);
            if (quoted != null && ((emailBody != null && emailBody.contains(quoted))
                    || (additionalNotes != null && additionalNotes.contains(quoted)))) {
                return new SettlementResolution(mode, null);
            }
            return new SettlementResolution(SettlementMode.MATERIAL_AND_LABOR,
                    "Az elszámolási mód AI-javaslatát nem támasztja alá ellenőrizhető szövegrész.");
        } catch (IllegalArgumentException exception) {
            return new SettlementResolution(SettlementMode.MATERIAL_AND_LABOR,
                    "Az AI által megadott elszámolási mód érvénytelen.");
        }
    }

    private boolean verifiedNumericEvidence(
            InputSource source,
            String evidence,
            BigDecimal value,
            String unit,
            String emailBody,
            String additionalNotes) {
        String quote = safeText(evidence);
        if (quote == null) {
            return false;
        }
        String sourceText = source == InputSource.CUSTOMER_EMAIL ? emailBody
                : source == InputSource.CUSTOMER_SUPPLEMENT ? additionalNotes : null;
        return sourceText != null && sourceText.contains(quote)
                && containsNumber(quote, value) && containsUnit(quote, unit);
    }

    private boolean containsUnit(String evidence, String unitCode) {
        QuantityUnit unit;
        try {
            unit = QuantityUnit.fromCode(unitCode);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        String expression = switch (unit) {
            case MILLIMETER -> "(?:(?<=\\d)mm|(?<![\\p{L}\\p{N}])mm)(?![\\p{L}\\p{N}])";
            case CENTIMETER -> "(?:(?<=\\d)cm|(?<![\\p{L}\\p{N}])cm)(?![\\p{L}\\p{N}])";
            case METER -> "(?:(?<=\\d)m(?![²23³])|(?<![\\p{L}\\p{N}])(?:m(?![²23³])|fm|folyóméter|folyometer|méter|meter))(?![\\p{L}\\p{N}])";
            case SQUARE_METER -> "(?:(?<=\\d)(?:m2|m²|nm)|(?<![\\p{L}\\p{N}])(?:m2|m²|nm|négyzetméter(?:es)?|negyzetmeter(?:es)?))(?![\\p{L}\\p{N}])";
            case CUBIC_METER -> "(?:(?<=\\d)(?:m3|m³)|(?<![\\p{L}\\p{N}])(?:m3|m³|köbméter|kobmeter))(?![\\p{L}\\p{N}])";
            case LITER -> "(?:(?<=\\d)l|(?<![\\p{L}\\p{N}])(?:l|liter|litre))(?![\\p{L}\\p{N}])";
            case SQUARE_METER_PER_LITER -> "(?:(?<=\\d)m(?:2|²)/l|(?<![\\p{L}\\p{N}])m(?:2|²)/l)(?![\\p{L}\\p{N}])";
            case EACH -> null;
            case PERCENT -> "(?:%|százalék|szazalek)";
            case SCALAR -> null;
            case KILOGRAM -> "(?:(?<=\\d)kg|(?<![\\p{L}\\p{N}])kg)(?![\\p{L}\\p{N}])";
            case HOUR -> "(?:(?<=\\d)h|(?<![\\p{L}\\p{N}])(?:h|óra|ora))(?![\\p{L}\\p{N}])";
        };
        return expression == null || Pattern.compile(expression, Pattern.CASE_INSENSITIVE)
                .matcher(evidence).find();
    }

    private boolean containsNumber(String text, BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        String numeric = normalized.toPlainString();
        for (String variant : List.of(numeric, numeric.replace('.', ','))) {
            String pattern = "(?<![\\p{L}\\p{N}])" + Pattern.quote(variant) + "(?![\\p{L}\\p{N}])";
            if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find()) {
                return true;
            }
        }
        if (normalized.scale() <= 0 && normalized.intValue() >= 0) {
            for (String word : HUNGARIAN_NUMBER_WORDS.getOrDefault(normalized.intValue(), List.of())) {
                String normalizedText = normalizeHungarian(text);
                if (Pattern.compile("(^|[^\\p{L}])" + Pattern.quote(normalizeHungarian(word))
                        + "([^\\p{L}]|$)").matcher(normalizedText).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    private String normalizeHungarian(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private String configuredUnit(CatalogCalculationConfig config, String id, String proposedUnit) {
        CatalogParameterConstraint constraint = config.parameterConstraints().get(id);
        return constraint == null ? safeText(proposedUnit) : constraint.unit();
    }

    private List<ChargeComponent> components(CatalogItemType type) {
        return switch (type) {
            case MATERIAL -> List.of(ChargeComponent.MATERIAL);
            case LABOR -> List.of(ChargeComponent.LABOR);
            case COMBINED -> List.of(ChargeComponent.MATERIAL, ChargeComponent.LABOR);
            case OTHER -> List.of(ChargeComponent.OTHER);
        };
    }

    private WorkRecognitionStatus recognitionStatus(WorkBuild work) {
        if (!work.reviewReasons.isEmpty()) {
            return WorkRecognitionStatus.NEEDS_REVIEW;
        }
        if (work.proposedLineCount == 0 || work.mappedLineCount == 0) {
            return WorkRecognitionStatus.UNMAPPED;
        }
        if (work.unmappedLineCount > 0) {
            return WorkRecognitionStatus.PARTIALLY_MAPPED;
        }
        return WorkRecognitionStatus.MAPPED;
    }

    private CalculationStatus overallStatus(
            DraftQuoteSnapshot snapshot, CostCalculationResult pricing) {
        if (snapshot.lineItems().stream().anyMatch(line ->
                line.calculationResult().status() == CalculationStatus.INVALID)) {
            return CalculationStatus.INVALID;
        }
        if (snapshot.lineItems().stream().anyMatch(line ->
                line.calculationResult().status() == CalculationStatus.NEEDS_INPUT)) {
            return CalculationStatus.NEEDS_INPUT;
        }
        if (snapshot.lineItems().stream().anyMatch(line ->
                line.calculationResult().status() == CalculationStatus.NEEDS_REVIEW)
                || snapshot.workItems().stream().anyMatch(work ->
                work.recognitionStatus() != WorkRecognitionStatus.MAPPED)) {
            return CalculationStatus.NEEDS_REVIEW;
        }
        if (pricing.pricingStatus() == PricingStatus.INVALID) {
            return CalculationStatus.INVALID;
        }
        if (pricing.pricingStatus() == PricingStatus.NEEDS_INPUT
                || pricing.netQuotePrice() == null
                || snapshot.lineItems().stream().noneMatch(DraftQuoteSnapshot.QuoteLineSnapshot::chargeable)) {
            return CalculationStatus.NEEDS_INPUT;
        }
        if (snapshot.lineItems().stream().filter(DraftQuoteSnapshot.QuoteLineSnapshot::chargeable)
                .anyMatch(line -> line.netLineTotal() == null)) {
            return CalculationStatus.NEEDS_INPUT;
        }
        return CalculationStatus.VALID;
    }

    private DraftQuoteSnapshot.QuoteLineSnapshot copyWithTotal(
            DraftQuoteSnapshot.QuoteLineSnapshot line, BigDecimal total) {
        return new DraftQuoteSnapshot.QuoteLineSnapshot(
                line.lineItemId(), line.workId(), line.catalogItemId(), line.catalogItem(),
                line.settlementMode(), line.requirements(), line.calculationPlan(), line.calculationResult(),
                line.requiredQuantity(), line.procurementQuantity(), line.customerChargeQuantity(),
                line.userOverrideQuantity(), line.assumptionsApproved(), line.approvedFingerprint(),
                line.explanation(), total, line.chargeable(), line.procurementPackageCount(),
                line.assumptions(), line.procurementOutputReference(),
                line.procurementPackageCountReference());
    }

    private CalculationResult invalidResult(String code, String message, String reference) {
        return new CalculationResult(CalculationStatus.INVALID, null,
                List.of(new CalculationIssue(code, message, reference)), List.of());
    }

    private String requirementId(String lineItemId, ChargeComponent component) {
        return lineItemId + "-" + component.name().toLowerCase(Locale.ROOT);
    }

    private Quantity zero(QuantityUnit unit) {
        return new Quantity(BigDecimal.ZERO, unit);
    }

    private InputSource parseSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            return InputSource.valueOf(source.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private CalculationOperation parseOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            return null;
        }
        try {
            return CalculationOperation.valueOf(operation.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private RoundingMode parseRoundingMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        try {
            return RoundingMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String formatMissingParameter(AiQuoteDtos.MissingParameter missing) {
        if (missing.id() == null || missing.id().isBlank()) {
            return null;
        }
        String label = missing.id() + (missing.unit() == null ? "" : " (" + missing.unit() + ")");
        return missing.reason() == null || missing.reason().isBlank()
                ? label : label + ": " + safeText(missing.reason());
    }

    private List<String> boundedStrings(List<String> values, int limit) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(limit)
                .map(this::safeText)
                .filter(Objects::nonNull)
                .toList();
    }

    private String requireDescription(String value) {
        String result = safeText(value);
        if (result == null) {
            throw new IllegalArgumentException("Each recognized work item requires a description.");
        }
        return result;
    }

    private String safeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() > MAX_TEXT_LENGTH
                ? normalized.substring(0, MAX_TEXT_LENGTH) : normalized;
    }

    private void requireSafeId(String value, String name) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("The AI result contains an invalid " + name + " identifier.");
        }
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? "A kalkuláció érvénytelen." : exception.getMessage();
    }

    public record BuildResult(
            DraftQuoteSnapshot snapshot,
            List<String> reviewWarnings,
            List<String> unmappedRequests,
            CalculationStatus calculationStatus,
            BigDecimal netQuotePrice,
            CostCalculationResult costResult) {
        public BuildResult {
            reviewWarnings = List.copyOf(reviewWarnings);
            unmappedRequests = List.copyOf(unmappedRequests);
        }
    }

    private record PlanPreparation(CalculationPlan plan, CalculationResult result) {
    }

    private record SourceResolution(InputSource source, String evidence, String issue) {
    }

    private record SettlementResolution(SettlementMode mode, String issue) {
    }

    private record LineBuild(
            DraftQuoteSnapshot.QuoteLineSnapshot snapshot,
            CalculationResult calculation,
            List<MaterialRequirement> materialRequirements,
            List<LaborRequirement> laborRequirements,
            List<OtherCost> otherCosts,
            QuoteLineItem customerQuoteLine) {
        private LineBuild {
            materialRequirements = List.copyOf(materialRequirements);
            laborRequirements = List.copyOf(laborRequirements);
            otherCosts = List.copyOf(otherCosts);
        }
    }

    private static final class WorkBuild {
        private final String workId;
        private final String description;
        private final InputSource source;
        private final Quantity requestedQuantity;
        private final String requestedUnit;
        private final SettlementMode settlementMode;
        private Long suggestedCatalogItemId;
        private final String sourceEvidence;
        private final String explanation;
        private final boolean explicitlyRequested;
        private final List<String> assumptions;
        private final List<String> missingParameters;
        private final List<String> reviewReasons;
        private final List<LineBuild> lineBuilds = new ArrayList<>();
        private final List<DraftQuoteSnapshot.UnmappedLineSnapshot> unmappedLines = new ArrayList<>();
        private int proposedLineCount;
        private int mappedLineCount;
        private int unmappedLineCount;
        private boolean multipleCatalogItems;

        private WorkBuild(
                String workId,
                String description,
                InputSource source,
                Quantity requestedQuantity,
                String requestedUnit,
                SettlementMode settlementMode,
                Long suggestedCatalogItemId,
                String sourceEvidence,
                String explanation,
                boolean explicitlyRequested,
                List<String> assumptions,
                List<String> missingParameters,
                List<String> reviewReasons) {
            this.workId = workId;
            this.description = description;
            this.source = source;
            this.requestedQuantity = requestedQuantity;
            this.requestedUnit = requestedUnit;
            this.settlementMode = settlementMode;
            this.suggestedCatalogItemId = suggestedCatalogItemId;
            this.sourceEvidence = sourceEvidence;
            this.explanation = explanation;
            this.explicitlyRequested = explicitlyRequested;
            this.assumptions = List.copyOf(assumptions);
            this.missingParameters = List.copyOf(missingParameters);
            this.reviewReasons = List.copyOf(reviewReasons);
        }
    }

    private static final class CalculationFailure extends RuntimeException {
        private CalculationFailure(String message) {
            super(message);
        }
    }
}
