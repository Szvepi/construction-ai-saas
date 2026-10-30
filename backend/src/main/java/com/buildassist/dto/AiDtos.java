package com.buildassist.dto;

import com.buildassist.calculation.CalculationStatus;
import com.buildassist.calculation.WorkRecognitionStatus;
import com.buildassist.dto.DraftDtos.SaveDraftRequest;

import java.math.BigDecimal;
import java.util.List;

public final class AiDtos {

    private AiDtos() {
    }

    public record DraftLineItem(
            String name,
            String unit,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal subtotal,
            String lineItemId,
            Long catalogItemId,
            String priceBasis,
            boolean priceOverridden,
            String priceOverrideReason,
            BigDecimal catalogUnitPrice,
            boolean assumptionsApproved,
            List<CalculationParameter> calculationParameters,
            boolean userOverrideLine,
            com.buildassist.calculation.CatalogItemType itemType
    ) {
        public DraftLineItem {
            calculationParameters = calculationParameters == null
                    ? List.of() : List.copyOf(calculationParameters);
        }

        public DraftLineItem(
                String name, String unit, BigDecimal quantity, BigDecimal unitPrice,
                BigDecimal subtotal, String lineItemId, Long catalogItemId) {
            this(name, unit, quantity, unitPrice, subtotal, lineItemId, catalogItemId,
                    "NET", false, null, unitPrice, false, List.of(), false, null);
        }

        public DraftLineItem(String name, String unit, BigDecimal quantity, BigDecimal unitPrice, BigDecimal subtotal) {
            this(name, unit, quantity, unitPrice, subtotal, null, null,
                    "NET", false, null, unitPrice, false, List.of(), false, null);
        }

        public DraftLineItem(
                String name, String unit, BigDecimal quantity, BigDecimal unitPrice, BigDecimal subtotal,
                String lineItemId, Long catalogItemId, String priceBasis,
                boolean priceOverridden, String priceOverrideReason) {
            this(name, unit, quantity, unitPrice, subtotal, lineItemId, catalogItemId,
                    priceBasis, priceOverridden, priceOverrideReason, unitPrice, false, List.of(), false, null);
        }

        public DraftLineItem(
                String name, String unit, BigDecimal quantity, BigDecimal unitPrice,
                BigDecimal subtotal, String lineItemId, Long catalogItemId, String priceBasis,
                boolean priceOverridden, String priceOverrideReason, BigDecimal catalogUnitPrice,
                boolean assumptionsApproved, List<CalculationParameter> calculationParameters) {
            this(name, unit, quantity, unitPrice, subtotal, lineItemId, catalogItemId, priceBasis,
                    priceOverridden, priceOverrideReason, catalogUnitPrice, assumptionsApproved,
                    calculationParameters, false, null);
        }
    }

    public record CalculationParameter(
            String id, BigDecimal value, String unit, String source, boolean approved, boolean rejected) {
        public CalculationParameter(
                String id, BigDecimal value, String unit, String source, boolean approved) {
            this(id, value, unit, source, approved, false);
        }
    }

    public enum CoverageStatus {
        INCLUDED,
        NEEDS_REVIEW,
        MISSING,
        EXCLUDED
    }

    public enum CoverageSource {
        EMAIL,
        ADDITIONAL_INFO,
        UNKNOWN
    }

    public record CoverageWorkItem(
            String workId,
            String name,
            String explanation,
            CoverageSource source,
            BigDecimal quantity,
            String unit,
            CoverageStatus status,
            String linkedLineItemId,
            boolean userConfirmed,
            List<String> linkedLineItemIds,
            WorkRecognitionStatus recognitionStatus,
            CalculationStatus calculationStatus) {
        public CoverageWorkItem {
            linkedLineItemIds = linkedLineItemIds == null ? List.of() : List.copyOf(linkedLineItemIds);
        }

        public CoverageWorkItem(
                String workId, String name, String explanation, CoverageSource source,
                BigDecimal quantity, String unit, CoverageStatus status,
                String linkedLineItemId, boolean userConfirmed) {
            this(workId, name, explanation, source, quantity, unit, status,
                    linkedLineItemId, userConfirmed, List.of(), null, null);
        }
    }

    public record CoverageWarning(String warningId, String text, boolean acknowledged) {
    }

    public record CoverageDecision(
            String issueId,
            CoverageStatus status,
            String lineItemId,
            String lineItemFingerprint,
            boolean userConfirmed
    ) {
    }

    public record CoverageDecisionRequest(
            CoverageStatus status,
            String lineItemId,
            SaveDraftRequest draft
    ) {
    }

    public record CoverageResponse(
            List<CoverageWorkItem> workItems,
            List<CoverageWarning> warnings,
            boolean analysisFresh,
            List<String> unresolvedIssues,
            String quoteFingerprint
    ) {
    }

    /**
     * Complete draft pricing information.
     */
    public record DraftPricingInfo(
            List<DraftLineItem> items,
            BigDecimal grandTotal
    ) {
    }
}
