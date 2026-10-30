package com.buildassist.calculation;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class DraftQuoteSnapshotValidator {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}");
    private static final Pattern FINGERPRINT = Pattern.compile("[a-fA-F0-9]{64}");
    private final CalculationPlanValidator planValidator = new CalculationPlanValidator();
    private final CatalogCalculationConfigValidator configValidator =
            new CatalogCalculationConfigValidator();

    public List<String> validate(DraftQuoteSnapshot snapshot) {
        if (snapshot == null) {
            return List.of("A quote snapshot is required.");
        }
        if (snapshot.fingerprint() == null || !FINGERPRINT.matcher(snapshot.fingerprint()).matches()) {
            return List.of("A quote snapshot requires a SHA-256 fingerprint.");
        }
        Set<String> workIds = new HashSet<>();
        for (DraftQuoteSnapshot.QuoteWorkSnapshot work : snapshot.workItems()) {
            if (work == null || !validId(work.workId()) || !workIds.add(work.workId())) {
                return List.of("Work identifiers must be present and unique.");
            }
            if (work.settlementMode() == null || work.source() == null) {
                return List.of("Each work item requires a source and settlement mode.");
            }
            if (work.source() == InputSource.CATALOG_DEFAULT
                    || work.source() == InputSource.CONTRACTOR_PROFILE
                    || work.source() == InputSource.USER_OVERRIDE) {
                return List.of("A work request cannot claim catalog, contractor, or override provenance.");
            }
            if (work.recognitionStatus() == null) {
                return List.of("Each work item requires a recognition status.");
            }
            if (work.customerRequestedQuantity() != null
                    && (work.customerRequestedUnit() == null || work.customerRequestedUnit().isBlank())) {
                return List.of("A requested quantity requires its unit.");
            }
            if (work.customerRequestedQuantity() != null) {
                try {
                    if (work.customerRequestedQuantity()
                            .convertTo(QuantityUnit.fromCode(work.customerRequestedUnit()))
                            .value().compareTo(work.customerRequestedQuantity().value()) != 0) {
                        return List.of("A requested quantity must match its stated unit.");
                    }
                } catch (IllegalArgumentException exception) {
                    return List.of("A requested quantity requires a supported unit.");
                }
            }
            if ((work.source() == InputSource.CUSTOMER_EMAIL
                    || work.source() == InputSource.CUSTOMER_SUPPLEMENT)
                    && (work.sourceEvidence() == null || work.sourceEvidence().isBlank())) {
                return List.of("A customer-sourced work item requires source evidence.");
            }
            if (work.source() == InputSource.AI_ASSUMPTION
                    && work.customerRequestedQuantity() != null) {
                return List.of("An AI assumption cannot be stored as a customer-requested quantity.");
            }
            if (work.unmappedLines().stream().anyMatch(line ->
                    line == null || line.reason() == null || line.reason().isBlank())) {
                return List.of("Each unmapped line must retain an explanation for its review status.");
            }
        }

        Set<String> lineIds = new HashSet<>();
        Set<String> requirementIds = new HashSet<>();
        Set<String> worksWithLines = new HashSet<>();
        for (DraftQuoteSnapshot.QuoteLineSnapshot line : snapshot.lineItems()) {
            if (line == null || !validId(line.lineItemId()) || !lineIds.add(line.lineItemId())) {
                return List.of("Quote line identifiers must be present and unique.");
            }
            if (!workIds.contains(line.workId())) {
                return List.of("Each quote line must reference an existing work item.");
            }
            worksWithLines.add(line.workId());
            if (line.catalogItemId() != null && line.catalogItem() == null) {
                return List.of("A catalog item reference requires a frozen catalog snapshot.");
            }
            if (line.chargeable()
                    && (line.catalogItemId() == null || line.catalogItem() == null
                    || line.catalogItem().netSellingUnitPrice() == null)) {
                return List.of("A chargeable quote line requires a frozen catalog price.");
            }
            DraftQuoteSnapshot.QuoteWorkSnapshot work = snapshot.workItems().stream()
                    .filter(item -> item.workId().equals(line.workId()))
                    .findFirst().orElseThrow();
            if (line.settlementMode() == null || line.settlementMode() != work.settlementMode()) {
                return List.of("A quote line must use its work item's settlement mode.");
            }
            if (line.catalogItem() != null && line.catalogItem().itemType() == null) {
                return List.of("A frozen catalog snapshot requires an item type.");
            }
            if (line.catalogItem() != null) {
                try {
                    QuantityUnit.fromCode(line.catalogItem().unit());
                } catch (IllegalArgumentException exception) {
                    return List.of("A frozen catalog snapshot requires a supported unit.");
                }
                if (line.catalogItem().netSellingUnitPrice() == null
                        || line.catalogItem().netSellingUnitPrice().signum() < 0
                        || (line.catalogItem().netProcurementUnitCost() != null
                        && line.catalogItem().netProcurementUnitCost().signum() < 0)
                        || (line.catalogItem().estimatedOwnLaborUnitCost() != null
                        && line.catalogItem().estimatedOwnLaborUnitCost().signum() < 0)) {
                    return List.of("Frozen catalog prices and costs must be non-negative.");
                }
                List<String> configErrors = configValidator.validate(line.catalogItem().calculationConfig());
                if (!configErrors.isEmpty()) {
                    return List.of("A frozen catalog calculation configuration is invalid.");
                }
            }
            if (line.calculationResult() == null) {
                return List.of("Each quote line must retain its calculation status.");
            }
            if (line.netLineTotal() != null && line.netLineTotal().signum() < 0) {
                return List.of("A quote line total cannot be negative.");
            }
            if (line.calculationResult().status() == CalculationStatus.VALID) {
                if (line.requiredQuantity() == null || line.calculationResult().quantity() == null) {
                    return List.of("A valid calculation must retain its calculated quantity.");
                }
                if (line.calculationPlan() == null
                        || !planValidator.validate(line.calculationPlan()).isEmpty()) {
                    return List.of("A valid calculation must retain a valid calculation plan.");
                }
                try {
                    if (line.requiredQuantity().convertTo(line.calculationResult().quantity().unit())
                            .value().compareTo(line.calculationResult().quantity().value()) != 0) {
                        return List.of("The stored required quantity must match the calculation result.");
                    }
                    if (line.catalogItem() != null) {
                        line.requiredQuantity().convertTo(QuantityUnit.fromCode(line.catalogItem().unit()));
                    }
                } catch (IllegalArgumentException exception) {
                    return List.of("The stored required quantity must use a compatible unit.");
                }
                if (line.userOverrideQuantity() != null) {
                    if (line.customerChargeQuantity() == null
                            || line.userOverrideQuantity().value().signum() <= 0) {
                        return List.of("A manual quantity override must be positive.");
                    }
                    try {
                        line.userOverrideQuantity().convertTo(line.customerChargeQuantity().unit());
                    } catch (IllegalArgumentException exception) {
                        return List.of("A manual quantity override must use a compatible unit.");
                    }
                }
                if (line.requirements().stream().anyMatch(requirement ->
                        requirement == null || requirement.requiredQuantity() == null
                                || requirement.customerChargeQuantity() == null
                                || (requirement.component() == ChargeComponent.MATERIAL
                                && requirement.procurementQuantity() == null))) {
                    return List.of("A valid calculation must retain all requirement quantities.");
                }
            } else if (line.requiredQuantity() != null
                    || line.procurementQuantity() != null
                    || line.customerChargeQuantity() != null
                    || line.userOverrideQuantity() != null
                    || line.netLineTotal() != null
                    || line.requirements().stream().anyMatch(requirement ->
                    requirement != null && (requirement.requiredQuantity() != null
                            || requirement.allowanceAdjustedQuantity() != null
                            || requirement.procurementQuantity() != null
                            || requirement.customerChargeQuantity() != null))) {
                return List.of("An incomplete or invalid calculation cannot retain a final quantity or price.");
            }
            if (line.requirements().isEmpty()) {
                return List.of("A quote line must cover at least one charge requirement.");
            }
            for (DraftQuoteSnapshot.RequirementSnapshot requirement : line.requirements()) {
                if (requirement == null || !validId(requirement.requirementId())
                        || !requirementIds.add(requirement.requirementId())) {
                    return List.of("Requirement identifiers must be present and charged at most once.");
                }
                if (requirement.component() == null) {
                    return List.of("Each quote requirement must have a charge component.");
                }
                if (requirement.requiredQuantity() != null
                        && requirement.requiredQuantity().value().signum() < 0) {
                    return List.of("Requirement quantities cannot be negative.");
                }
                if (line.calculationResult().status() == CalculationStatus.VALID) {
                    if (requirement.component() == ChargeComponent.MATERIAL
                            && requirement.allowanceAdjustedQuantity() == null) {
                        return List.of("A valid material requirement must retain its allowance-adjusted quantity.");
                    }
                    if (requirement.component() != ChargeComponent.MATERIAL
                            && requirement.allowanceAdjustedQuantity() != null) {
                        return List.of("Only material requirements may have an allowance-adjusted quantity.");
                    }
                    try {
                        QuantityUnit resultUnit = line.requiredQuantity().unit();
                        requirement.requiredQuantity().convertTo(resultUnit);
                        requirement.customerChargeQuantity().convertTo(resultUnit);
                        if (requirement.component() == ChargeComponent.MATERIAL) {
                            requirement.allowanceAdjustedQuantity().convertTo(resultUnit);
                            requirement.procurementQuantity().convertTo(resultUnit);
                        } else if (requirement.procurementQuantity() != null) {
                            return List.of("Only material requirements may have procurement quantities.");
                        }
                    } catch (IllegalArgumentException exception) {
                        return List.of("Requirement quantities must use compatible units.");
                    }
                }
                if (requirement.component() == ChargeComponent.OTHER
                        && requirement.netUnitCost() != null) {
                    return List.of("Other costs must be persisted as direct amounts, not unit costs.");
                }
                if (requirement.component() != ChargeComponent.OTHER
                        && requirement.directCost() != null) {
                    return List.of("Only other costs may contain a fixed direct cost.");
                }
                if ((requirement.netUnitCost() != null && requirement.netUnitCost().signum() < 0)
                        || (requirement.directCost() != null && requirement.directCost().signum() < 0)) {
                    return List.of("Requirement cost values cannot be negative.");
                }
                if (line.settlementMode() != SettlementMode.MATERIAL_AND_LABOR
                        && requirement.component() == ChargeComponent.MATERIAL && line.chargeable()) {
                    return List.of("The work's settlement mode does not allow a customer material charge.");
                }
                if (line.catalogItem() != null
                        && !supports(line.catalogItem().itemType(), requirement.component())) {
                    return List.of("A catalog item cannot be charged as an incompatible component.");
                }
            }
            if (line.assumptionsApproved()
                    && (line.approvedFingerprint() == null
                    || !FINGERPRINT.matcher(line.approvedFingerprint()).matches())) {
                return List.of("Approved assumptions must reference a SHA-256 calculation fingerprint.");
            }
            if (!line.chargeable() && line.netLineTotal() != null
                    && line.netLineTotal().signum() != 0) {
                return List.of("A non-chargeable quote line cannot have a line total.");
            }
            if (line.procurementPackageCount() != null
                    && line.procurementPackageCount().unit() != QuantityUnit.EACH) {
                return List.of("A procurement package count must use the item-count unit.");
            }
            if (line.calculationPlan() != null) {
                List<String> provenanceErrors = validateInputProvenance(
                        line.calculationPlan(), line.catalogItem(),
                        line.calculationResult().status() == CalculationStatus.VALID);
                if (!provenanceErrors.isEmpty()) {
                    return provenanceErrors;
                }
                if (line.calculationResult().status() == CalculationStatus.VALID) {
                    List<String> operationErrors = validateOperations(
                            line.calculationPlan(), line.catalogItem());
                    if (!operationErrors.isEmpty()) {
                        return operationErrors;
                    }
                }
            }
        }
        for (DraftQuoteSnapshot.QuoteWorkSnapshot work : snapshot.workItems()) {
            if (work.recognitionStatus() == WorkRecognitionStatus.MAPPED
                    && !worksWithLines.contains(work.workId())) {
                return List.of("A mapped work item must have at least one directly linked quote line.");
            }
        }
        return List.of();
    }

    private List<String> validateOperations(
            CalculationPlan plan, DraftQuoteSnapshot.CatalogItemSnapshot catalogItem) {
        Set<CalculationOperation> allowed = catalogItem == null
                || catalogItem.calculationConfig() == null
                || catalogItem.calculationConfig().allowedCalculations().isEmpty()
                ? Set.of(CalculationOperation.DIRECT)
                : catalogItem.calculationConfig().allowedCalculations();
        for (CalculationStep step : plan.steps()) {
            if (step == null || step.operation() == null) {
                continue;
            }
            boolean configuredAllowance = "miraMaterialAllowance".equals(step.id())
                    && step.operation() == CalculationOperation.PERCENT_ADJUSTMENT
                    && catalogItem != null && catalogItem.calculationConfig() != null
                    && catalogItem.calculationConfig().materialAllowancePercent() != null;
            if (!allowed.contains(step.operation()) && !configuredAllowance) {
                return List.of("The calculation plan contains an operation not allowed by the frozen catalog.");
            }
        }
        return List.of();
    }

    private List<String> validateInputProvenance(
            CalculationPlan plan,
            DraftQuoteSnapshot.CatalogItemSnapshot catalogItem,
            boolean strictUnits) {
        Map<String, CatalogParameterDefault> defaults = catalogItem == null
                || catalogItem.calculationConfig() == null
                ? java.util.Map.of() : catalogItem.calculationConfig().defaultParameters();
        for (CalculationInput input : plan.inputs()) {
            if (input == null || input.source() == null) {
                return List.of("Each calculation input must retain its source.");
            }
            if (strictUnits && input.value() != null) {
                try {
                    new Quantity(input.value(), QuantityUnit.fromCode(input.unit()));
                } catch (IllegalArgumentException exception) {
                    return List.of("A calculation input has an invalid value or unit.");
                }
            }
            if ((input.source() == InputSource.CUSTOMER_EMAIL
                    || input.source() == InputSource.CUSTOMER_SUPPLEMENT)
                    && input.value() != null
                    && (!input.approved() || input.evidence() == null || input.evidence().isBlank())) {
                return List.of("A customer-provided value requires validated evidence.");
            }
            if (input.source() == InputSource.AI_ASSUMPTION && input.approved()) {
                return List.of("An AI assumption cannot be trusted without an explicit user decision.");
            }
            if (input.source() == InputSource.USER_OVERRIDE) {
                return List.of("An AI calculation plan cannot claim a user override as an input.");
            }
            if ((input.source() == InputSource.CATALOG_DEFAULT
                    || input.source() == InputSource.CONTRACTOR_PROFILE)
                    && !input.approved()) {
                return List.of("A backend-calculated or configured input must be verified.");
            }
            if (input.source() == InputSource.CATALOG_DEFAULT
                    || input.source() == InputSource.CONTRACTOR_PROFILE) {
                CatalogParameterDefault configured = defaults.get(input.id());
                boolean materialAllowance = "miraMaterialAllowancePercent".equals(input.id())
                        && catalogItem != null && catalogItem.calculationConfig() != null
                        && catalogItem.calculationConfig().materialAllowancePercent() != null;
                if (!input.approved() || (configured == null && !materialAllowance)) {
                    return List.of("A configured input source must exist in the frozen catalog snapshot.");
                }
                if (configured != null && (!configured.value().equals(input.value())
                        || !configured.unit().equals(input.unit())
                        || configured.source() != input.source())) {
                    return List.of("A configured input must match the frozen catalog default.");
                }
                if (materialAllowance && input.value().compareTo(
                        catalogItem.calculationConfig().materialAllowancePercent()) != 0) {
                    return List.of("The material allowance must match the frozen catalog configuration.");
                }
            }
        }
        return List.of();
    }

    private boolean supports(CatalogItemType itemType, ChargeComponent component) {
        return switch (itemType) {
            case MATERIAL -> component == ChargeComponent.MATERIAL;
            case LABOR -> component == ChargeComponent.LABOR;
            case COMBINED -> component == ChargeComponent.MATERIAL || component == ChargeComponent.LABOR;
            case OTHER -> component == ChargeComponent.OTHER;
        };
    }

    private boolean validId(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }
}
