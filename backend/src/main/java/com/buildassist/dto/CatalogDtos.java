package com.buildassist.dto;

import com.buildassist.calculation.CatalogCalculationConfig;
import com.buildassist.calculation.CatalogItemType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CreateCatalogItemRequest(
        @NotBlank @Size(max = 255)
        String name,
        @NotNull @DecimalMin("0.00")
        BigDecimal netSellingUnitPrice,
        @NotBlank @Size(max = 50)
        String unit,
        @NotNull
        CatalogItemType itemType,
        @DecimalMin("0.00")
        BigDecimal netProcurementUnitCost,
        @DecimalMin("0.00")
        BigDecimal estimatedOwnLaborUnitCost,
        CatalogCalculationConfig calculationConfig) {
    }

    public record UpdateCatalogItemRequest(
        @NotBlank @Size(max = 255)
        String name,
        @NotNull @DecimalMin("0.00")
        BigDecimal netSellingUnitPrice,
        @NotBlank @Size(max = 50)
        String unit,
        @NotNull
        CatalogItemType itemType,
        @DecimalMin("0.00")
        BigDecimal netProcurementUnitCost,
        @DecimalMin("0.00")
        BigDecimal estimatedOwnLaborUnitCost,
        CatalogCalculationConfig calculationConfig) {
    }

    public record CatalogItemResponse(
        Long id,
        String name,
        BigDecimal netSellingUnitPrice,
        String unit,
        CatalogItemType itemType,
        BigDecimal netProcurementUnitCost,
        BigDecimal estimatedOwnLaborUnitCost,
        CatalogCalculationConfig calculationConfig,
        Instant createdAt) {
    }
}
