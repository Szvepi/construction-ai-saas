package com.buildassist.dto;

import com.buildassist.dto.DraftDtos.SaveDraftRequest;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

public final class AiDtos {

    private AiDtos() {
    }

    /**
     * Extracted item from the email by AI model - represents a line item requested in the email.
     */
    public record AiExtractedItem(
            @JsonProperty("catalog_item_id")
            Long catalogItemId,
            @JsonProperty("quantity")
            BigDecimal quantity
    ) {
    }

    /**
     * Result of AI extraction - structured JSON response from LLM containing list of requested items.
     */
    public record AiExtractionResult(
            @JsonProperty("client_name")
            String clientName,
            @JsonProperty("items")
            List<AiExtractedItem> items,
            @JsonProperty("unmapped_requests")
            List<String> unmappedRequests, // Olyan kérések, amik nincsenek a katalógusban
            @JsonProperty("review_warnings")
            List<String> reviewWarnings,  // Megjegyzések, amikre érdemes figyelni a felhasználónak.
            @JsonProperty("requested_works")
            List<AiRequestedWork> requestedWorks
    ) {
        public AiExtractionResult(String clientName, List<AiExtractedItem> items,
                                  List<String> unmappedRequests, List<String> reviewWarnings) {
            this(clientName, items, unmappedRequests, reviewWarnings, List.of());
        }
    }

    public record AiRequestedWork(
            @JsonProperty("name")
            String name,
            @JsonProperty("source")
            String source,
            @JsonProperty("catalog_item_id")
            Long catalogItemId,
            @JsonProperty("quantity")
            BigDecimal quantity,
            @JsonProperty("unit")
            String unit,
            @JsonProperty("reason")
            String reason
    ) {
    }

    /**
     * Line item for draft cost breakdown - contains catalog item info and calculated subtotal.
     */
    public record DraftLineItem(
            String name,
            String unit,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal subtotal,
            String lineItemId,
            Long catalogItemId
    ) {
        public DraftLineItem(String name, String unit, BigDecimal quantity, BigDecimal unitPrice, BigDecimal subtotal) {
            this(name, unit, quantity, unitPrice, subtotal, null, null);
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

    public record RequestedWork(
            String workId,
            String name,
            String explanation,
            CoverageSource source,
            BigDecimal quantity,
            String unit,
            Long suggestedCatalogItemId,
            String suggestedCatalogItemName,
            String suggestedCalculationStrategy
    ) {
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
            boolean userConfirmed
    ) {
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
