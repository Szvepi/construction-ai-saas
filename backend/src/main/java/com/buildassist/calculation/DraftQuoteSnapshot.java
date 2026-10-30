package com.buildassist.calculation;

import java.math.BigDecimal;
import java.util.List;

public record DraftQuoteSnapshot(
        int schemaVersion,
        String fingerprint,
        List<QuoteWorkSnapshot> workItems,
        List<QuoteLineSnapshot> lineItems) {

    public static final int CURRENT_SCHEMA_VERSION = 4;

    public DraftQuoteSnapshot {
        workItems = workItems == null ? List.of() : List.copyOf(workItems);
        lineItems = lineItems == null ? List.of() : List.copyOf(lineItems);
    }

    public boolean approvalIsCurrent(QuoteLineSnapshot line) {
        return line != null && line.assumptionsApproved()
                && fingerprint != null && fingerprint.equals(line.approvedFingerprint());
    }

    public record QuoteWorkSnapshot(
            String workId,
            String originalRequest,
            InputSource source,
            Quantity customerRequestedQuantity,
            String customerRequestedUnit,
            SettlementMode settlementMode,
            WorkRecognitionStatus recognitionStatus,
            Long suggestedCatalogItemId,
            String sourceEvidence,
            String explanation,
            boolean explicitlyRequested,
            List<String> assumptions,
            List<String> missingParameters,
            List<UnmappedLineSnapshot> unmappedLines) {

        public QuoteWorkSnapshot {
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            missingParameters = missingParameters == null ? List.of() : List.copyOf(missingParameters);
            unmappedLines = unmappedLines == null ? List.of() : List.copyOf(unmappedLines);
        }

        public QuoteWorkSnapshot(
                String workId,
                String originalRequest,
                InputSource source,
                Quantity customerRequestedQuantity,
                String customerRequestedUnit,
                SettlementMode settlementMode,
                WorkRecognitionStatus recognitionStatus,
                Long suggestedCatalogItemId,
                String sourceEvidence,
                String explanation,
                boolean explicitlyRequested,
                List<String> assumptions,
                List<String> missingParameters) {
            this(workId, originalRequest, source, customerRequestedQuantity, customerRequestedUnit,
                    settlementMode, recognitionStatus, suggestedCatalogItemId, sourceEvidence, explanation,
                    explicitlyRequested, assumptions, missingParameters, List.of());
        }

        public QuoteWorkSnapshot(
                String workId,
                String originalRequest,
                InputSource source,
                Quantity customerRequestedQuantity,
                String customerRequestedUnit,
                SettlementMode settlementMode) {
            this(workId, originalRequest, source, customerRequestedQuantity, customerRequestedUnit,
                    settlementMode, WorkRecognitionStatus.MAPPED, null, null, null, true, List.of(), List.of());
        }
    }

    public record UnmappedLineSnapshot(
            String unmappedId,
            String explanation,
            String reason,
            List<String> assumptions,
            CalculationPlan suggestedCalculationPlan) {
        public UnmappedLineSnapshot {
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        }

        public UnmappedLineSnapshot(
                String unmappedId, String explanation, String reason, List<String> assumptions) {
            this(unmappedId, explanation, reason, assumptions, null);
        }

        public UnmappedLineSnapshot(String explanation, String reason, List<String> assumptions) {
            this(null, explanation, reason, assumptions, null);
        }
    }

    public record QuoteLineSnapshot(
            String lineItemId,
            String workId,
            Long catalogItemId,
            CatalogItemSnapshot catalogItem,
            SettlementMode settlementMode,
            List<RequirementSnapshot> requirements,
            CalculationPlan calculationPlan,
            CalculationResult calculationResult,
            Quantity requiredQuantity,
            Quantity procurementQuantity,
            Quantity customerChargeQuantity,
            Quantity userOverrideQuantity,
            boolean assumptionsApproved,
            String approvedFingerprint,
            String explanation,
            BigDecimal netLineTotal,
            boolean chargeable,
            Quantity procurementPackageCount,
            List<String> assumptions,
            String procurementOutputReference,
            String procurementPackageCountReference,
            BigDecimal manualNetUnitPrice,
            String manualPriceReason,
            boolean userOverrideLine) {

        public QuoteLineSnapshot {
            requirements = requirements == null ? List.of() : List.copyOf(requirements);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        }

        public QuoteLineSnapshot(
                String lineItemId, String workId, Long catalogItemId, CatalogItemSnapshot catalogItem,
                SettlementMode settlementMode, List<RequirementSnapshot> requirements,
                CalculationPlan calculationPlan, CalculationResult calculationResult,
                Quantity requiredQuantity, Quantity procurementQuantity, Quantity customerChargeQuantity,
                Quantity userOverrideQuantity, boolean assumptionsApproved, String approvedFingerprint,
                String explanation, BigDecimal netLineTotal, boolean chargeable,
                Quantity procurementPackageCount, List<String> assumptions,
                String procurementOutputReference, String procurementPackageCountReference) {
            this(lineItemId, workId, catalogItemId, catalogItem, settlementMode, requirements,
                    calculationPlan, calculationResult, requiredQuantity, procurementQuantity,
                    customerChargeQuantity, userOverrideQuantity, assumptionsApproved, approvedFingerprint,
                    explanation, netLineTotal, chargeable, procurementPackageCount, assumptions,
                    procurementOutputReference, procurementPackageCountReference, null, null, false);
        }

        public QuoteLineSnapshot(
                String lineItemId, String workId, Long catalogItemId, CatalogItemSnapshot catalogItem,
                SettlementMode settlementMode, List<RequirementSnapshot> requirements,
                CalculationPlan calculationPlan, CalculationResult calculationResult,
                Quantity requiredQuantity, Quantity procurementQuantity, Quantity customerChargeQuantity,
                Quantity userOverrideQuantity, boolean assumptionsApproved, String approvedFingerprint,
                String explanation, BigDecimal netLineTotal, boolean chargeable,
                Quantity procurementPackageCount, List<String> assumptions,
                String procurementOutputReference, String procurementPackageCountReference,
                BigDecimal manualNetUnitPrice, String manualPriceReason) {
            this(lineItemId, workId, catalogItemId, catalogItem, settlementMode, requirements,
                    calculationPlan, calculationResult, requiredQuantity, procurementQuantity,
                    customerChargeQuantity, userOverrideQuantity, assumptionsApproved, approvedFingerprint,
                    explanation, netLineTotal, chargeable, procurementPackageCount, assumptions,
                    procurementOutputReference, procurementPackageCountReference,
                    manualNetUnitPrice, manualPriceReason, false);
        }

        public QuoteLineSnapshot(
                String lineItemId,
                String workId,
                Long catalogItemId,
                CatalogItemSnapshot catalogItem,
                SettlementMode settlementMode,
                List<RequirementSnapshot> requirements,
                CalculationPlan calculationPlan,
                CalculationResult calculationResult,
                Quantity requiredQuantity,
                Quantity procurementQuantity,
                Quantity customerChargeQuantity,
                Quantity userOverrideQuantity,
                boolean assumptionsApproved,
                String approvedFingerprint,
                String explanation,
                BigDecimal netLineTotal,
                boolean chargeable,
                Quantity procurementPackageCount,
                List<String> assumptions) {
            this(lineItemId, workId, catalogItemId, catalogItem, settlementMode, requirements,
                    calculationPlan, calculationResult, requiredQuantity, procurementQuantity,
                    customerChargeQuantity, userOverrideQuantity, assumptionsApproved, approvedFingerprint,
                    explanation, netLineTotal, chargeable, procurementPackageCount, assumptions,
                    null, null, null, null, false);
        }

        public QuoteLineSnapshot(
                String lineItemId,
                String workId,
                Long catalogItemId,
                CatalogItemSnapshot catalogItem,
                SettlementMode settlementMode,
                List<RequirementSnapshot> requirements,
                CalculationPlan calculationPlan,
                CalculationResult calculationResult,
                Quantity requiredQuantity,
                Quantity procurementQuantity,
                Quantity customerChargeQuantity,
                Quantity userOverrideQuantity,
                boolean assumptionsApproved,
                String approvedFingerprint,
                String explanation,
                BigDecimal netLineTotal) {
            this(lineItemId, workId, catalogItemId, catalogItem, settlementMode, requirements,
                    calculationPlan, calculationResult, requiredQuantity, procurementQuantity,
                    customerChargeQuantity, userOverrideQuantity, assumptionsApproved, approvedFingerprint,
                    explanation, netLineTotal, true, null, List.of(), null, null, null, null);
        }
    }

    public record CatalogItemSnapshot(
            String name,
            String unit,
            CatalogItemType itemType,
            BigDecimal netSellingUnitPrice,
            BigDecimal netProcurementUnitCost,
            BigDecimal estimatedOwnLaborUnitCost,
            CatalogCalculationConfig calculationConfig) {
    }

    public record RequirementSnapshot(
            String requirementId,
            ChargeComponent component,
            Quantity requiredQuantity,
            Quantity allowanceAdjustedQuantity,
            Quantity procurementQuantity,
            Quantity customerChargeQuantity,
            BigDecimal netUnitCost,
            BigDecimal directCost) {
        public RequirementSnapshot(
                String requirementId,
                ChargeComponent component,
                Quantity requiredQuantity,
                Quantity procurementQuantity,
                Quantity customerChargeQuantity,
                BigDecimal netUnitCost,
                BigDecimal directCost) {
            this(requirementId, component, requiredQuantity,
                    component == ChargeComponent.MATERIAL ? requiredQuantity : null,
                    procurementQuantity, customerChargeQuantity, netUnitCost, directCost);
        }

    }
}
