package com.buildassist.service;

import com.buildassist.calculation.CalculationInput;
import com.buildassist.calculation.CalculationPlan;
import com.buildassist.calculation.CalculationResult;
import com.buildassist.calculation.CalculationStatus;
import com.buildassist.calculation.ChargeComponent;
import com.buildassist.calculation.CatalogCalculationConfig;
import com.buildassist.calculation.DeterministicQuantityCalculator;
import com.buildassist.calculation.DraftQuoteSnapshot;
import com.buildassist.calculation.DraftQuoteSnapshot.QuoteLineSnapshot;
import com.buildassist.calculation.InputSource;
import com.buildassist.calculation.Quantity;
import com.buildassist.calculation.QuantityUnit;
import com.buildassist.dto.AiDtos.CalculationParameter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class DraftQuoteLineEditor {

    private static final String REJECTED_ASSUMPTION_EVIDENCE = "USER_REJECTED_ASSUMPTION";
    private final DeterministicQuantityCalculator calculator = new DeterministicQuantityCalculator();

    public QuoteLineSnapshot apply(
            QuoteLineSnapshot line,
            List<CalculationParameter> submittedParameters,
            boolean approveAssumptions) {
        Objects.requireNonNull(line, "line");
        if (line.calculationPlan() == null) {
            if (submittedParameters != null && !submittedParameters.isEmpty()) {
                throw new IllegalArgumentException("Ehhez a sorhoz nem módosítható kalkulációs bemenet.");
            }
            return line;
        }
        Map<String, CalculationParameter> submittedById = new java.util.HashMap<>();
        if (submittedParameters != null) {
            for (CalculationParameter parameter : submittedParameters) {
                if (parameter == null || parameter.id() == null
                        || submittedById.putIfAbsent(parameter.id(), parameter) != null) {
                    throw new IllegalArgumentException("A kalkulációs paraméterek azonosítói hiányoznak vagy duplikáltak.");
                }
            }
        }

        boolean inputValueChanged = line.calculationPlan().inputs().stream()
                .map(input -> submittedById.get(input.id()))
                .filter(Objects::nonNull)
                .anyMatch(input -> !Objects.equals(input.value(),
                        line.calculationPlan().inputs().stream()
                                .filter(original -> original.id().equals(input.id()))
                                .findFirst().orElseThrow().value()));
        boolean effectiveApproval = approveAssumptions && !inputValueChanged;
        List<CalculationInput> inputs = new ArrayList<>();
        for (CalculationInput original : line.calculationPlan().inputs()) {
            CalculationParameter submitted = submittedById.remove(original.id());
            if (submitted == null) {
                inputs.add(original);
                continue;
            }
            if (!Objects.equals(original.unit(), submitted.unit())) {
                throw new IllegalArgumentException("A kalkulációs paraméter mértékegysége nem módosítható.");
            }
            InputSource source = original.source();
            BigDecimal value = submitted.value();
            boolean approved = original.approved();
            if (submitted.rejected()) {
                if (source != InputSource.AI_ASSUMPTION) {
                    throw new IllegalArgumentException("Csak AI-feltételezés utasítható el.");
                }
                value = null;
                approved = false;
                inputs.add(new CalculationInput(
                        original.id(), null, original.unit(), InputSource.AI_ASSUMPTION,
                        false, REJECTED_ASSUMPTION_EVIDENCE));
                continue;
            } else if (source == InputSource.AI_ASSUMPTION) {
                if (submitted.source() != null
                        && InputSource.USER_OVERRIDE.name().equals(submitted.source())
                        && value != null && !Objects.equals(original.value(), value)) {
                    source = InputSource.USER_OVERRIDE;
                    approved = true;
                } else {
                    approved = effectiveApproval && value != null;
                }
            } else if (!Objects.equals(original.value(), value)) {
                source = InputSource.USER_OVERRIDE;
                approved = true;
            }
            validateConstraint(line, original.id(), value, submitted.unit());
            inputs.add(new CalculationInput(
                    original.id(), value, original.unit(), source, approved,
                    source == InputSource.USER_OVERRIDE ? "Kivitelezői kézi módosítás"
                            : original.evidence()));
        }
        if (!submittedById.isEmpty()) {
            throw new IllegalArgumentException("A kérés nem módosítható kalkulációs paramétert tartalmaz.");
        }

        boolean unresolvedAiAssumption = inputs.stream().anyMatch(input ->
                input.source() == InputSource.AI_ASSUMPTION && input.value() != null && !input.approved());
        List<String> reviewReasons = line.calculationPlan().reviewReasons().stream()
                .filter(reason -> !reason.startsWith("AI-javaslat,") || unresolvedAiAssumption)
                .toList();
        CalculationPlan plan = new CalculationPlan(
                inputs, line.calculationPlan().steps(),
                line.calculationPlan().outputReference(), reviewReasons);
        CalculationResult result = calculator.calculate(plan);
        if (result.status() != CalculationStatus.VALID) {
            List<DraftQuoteSnapshot.RequirementSnapshot> incomplete = line.requirements().stream()
                    .map(requirement -> new DraftQuoteSnapshot.RequirementSnapshot(
                            requirement.requirementId(), requirement.component(), null, null, null,
                            requirement.netUnitCost(), requirement.directCost()))
                    .toList();
            return copy(line, plan, result, null, null, null, null,
                    incomplete, null, null, false, null);
        }

        Quantity required = result.quantity().convertTo(QuantityUnit.fromCode(line.catalogItem().unit()));
        CatalogCalculationConfig config = line.catalogItem().calculationConfig();
        BigDecimal allowancePercent = config == null ? null : config.materialAllowancePercent();
        Quantity adjusted = allowancePercent == null || allowancePercent.signum() == 0
                ? required
                : new Quantity(required.value().multiply(BigDecimal.ONE.add(allowancePercent.movePointLeft(2))),
                        required.unit());
        Quantity procurement = null;
        Quantity packageCount = null;
        if (hasMaterial(line)) {
            if (line.settlementMode()
                    == com.buildassist.calculation.SettlementMode.CUSTOMER_SUPPLIED_MATERIAL) {
                procurement = new Quantity(BigDecimal.ZERO, required.unit());
            } else if (line.procurementOutputReference() != null) {
                procurement = calculateOutput(plan, line.procurementOutputReference())
                        .quantity().convertTo(required.unit());
            } else {
                procurement = adjusted;
            }
        }
        if (line.procurementPackageCountReference() != null) {
            CalculationResult packageResult = calculateOutput(plan, line.procurementPackageCountReference());
            if (packageResult.status() != CalculationStatus.VALID
                    || packageResult.quantity().unit() != QuantityUnit.EACH
                    || packageResult.quantity().value().stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("A beszerzési kiszerelések darabszáma érvénytelen.");
            }
            packageCount = packageResult.quantity();
        }

        List<DraftQuoteSnapshot.RequirementSnapshot> requirements = new ArrayList<>();
        for (DraftQuoteSnapshot.RequirementSnapshot requirement : line.requirements()) {
            Quantity charge = requirement.component() == ChargeComponent.MATERIAL
                    ? (line.settlementMode()
                    == com.buildassist.calculation.SettlementMode.MATERIAL_AND_LABOR ? adjusted : required)
                    : required;
            Quantity requirementProcurement = requirement.component() == ChargeComponent.MATERIAL
                    ? procurement : null;
            requirements.add(new DraftQuoteSnapshot.RequirementSnapshot(
                    requirement.requirementId(), requirement.component(), required,
                    requirement.component() == ChargeComponent.MATERIAL ? adjusted : null,
                    requirementProcurement, charge, requirement.netUnitCost(), requirement.directCost()));
        }
        Quantity customerCharge = line.catalogItem().itemType()
                == com.buildassist.calculation.CatalogItemType.MATERIAL
                ? (line.settlementMode()
                == com.buildassist.calculation.SettlementMode.MATERIAL_AND_LABOR ? adjusted : required)
                : required;

        boolean approved = effectiveApproval
                && inputs.stream().noneMatch(input -> input.source() == InputSource.AI_ASSUMPTION
                && !input.approved());
        String approvedFingerprint = approved ? fingerprint(plan) : null;
        return copy(line, plan, result, required, procurement, customerCharge, packageCount,
                requirements, adjusted, approvedFingerprint, approved, null);
    }

    private CalculationResult calculateOutput(CalculationPlan plan, String reference) {
        return calculator.calculate(new CalculationPlan(
                plan.inputs(), plan.steps(), reference, plan.reviewReasons()));
    }

    private void validateConstraint(
            QuoteLineSnapshot line, String id, BigDecimal value, String unit) {
        if (value == null || line.catalogItem() == null
                || line.catalogItem().calculationConfig() == null) {
            return;
        }
        var constraint = line.catalogItem().calculationConfig().parameterConstraints().get(id);
        if (constraint == null) {
            return;
        }
        Quantity converted = new Quantity(value, QuantityUnit.fromCode(unit))
                .convertTo(QuantityUnit.fromCode(constraint.unit()));
        if ((constraint.minimum() != null && converted.value().compareTo(constraint.minimum()) < 0)
                || (constraint.maximum() != null && converted.value().compareTo(constraint.maximum()) > 0)) {
            throw new IllegalArgumentException("A megadott paraméter kívül esik a katalógusban rögzített tartományon.");
        }
    }

    private boolean hasMaterial(QuoteLineSnapshot line) {
        return line.requirements().stream()
                .anyMatch(requirement -> requirement.component() == ChargeComponent.MATERIAL);
    }

    private String fingerprint(CalculationPlan plan) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(plan.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private QuoteLineSnapshot copy(
            QuoteLineSnapshot line, CalculationPlan plan, CalculationResult result,
            Quantity required, Quantity procurement, Quantity customerCharge, Quantity packageCount,
            List<DraftQuoteSnapshot.RequirementSnapshot> requirements, Quantity allowanceAdjusted,
            String approvedFingerprint, boolean assumptionsApproved, BigDecimal netLineTotal) {
        return new QuoteLineSnapshot(
                line.lineItemId(), line.workId(), line.catalogItemId(), line.catalogItem(),
                line.settlementMode(), requirements, plan, result, required, procurement,
                customerCharge,
                result.status() == CalculationStatus.VALID ? line.userOverrideQuantity() : null,
                assumptionsApproved,
                approvedFingerprint, line.explanation(), netLineTotal, line.chargeable(),
                packageCount, line.assumptions(), line.procurementOutputReference(),
                line.procurementPackageCountReference(), line.manualNetUnitPrice(), line.manualPriceReason(),
                line.userOverrideLine());
    }
}
