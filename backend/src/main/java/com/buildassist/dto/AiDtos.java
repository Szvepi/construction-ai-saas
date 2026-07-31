package com.buildassist.dto;

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
            @JsonProperty("items")
            List<AiExtractedItem> items
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
            BigDecimal subtotal
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
