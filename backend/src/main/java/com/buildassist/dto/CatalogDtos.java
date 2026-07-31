package com.buildassist.dto;

import com.buildassist.model.CalculationStrategy;
import java.math.BigDecimal;
import java.time.Instant;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CreateCatalogItemRequest(
        String name,
        BigDecimal unitPrice,
        String unit,
        CalculationStrategy calculationStrategy) {
    }

    public record UpdateCatalogItemRequest(
        String name,
        BigDecimal unitPrice,
        String unit,
        CalculationStrategy calculationStrategy) {
    }

    public record CatalogItemResponse(
        Long id,
        String name,
        BigDecimal unitPrice,
        String unit,
        CalculationStrategy calculationStrategy,
        Instant createdAt) {
    }
}
