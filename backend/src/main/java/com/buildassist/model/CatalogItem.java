package com.buildassist.model;

import com.buildassist.calculation.CatalogCalculationConfig;
import com.buildassist.calculation.CatalogItemType;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Entity
@Table(name = "catalog_items")
public class CatalogItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal netSellingUnitPrice;

    @Column(nullable = false)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 20)
    private CatalogItemType itemType = CatalogItemType.OTHER;

    @Column(name = "net_procurement_unit_cost", precision = 12, scale = 2)
    private BigDecimal netProcurementUnitCost;

    @Column(name = "estimated_own_labor_unit_cost", precision = 12, scale = 2)
    private BigDecimal estimatedOwnLaborUnitCost;

    @Column(name = "calculation_config", columnDefinition = "TEXT")
    private String calculationConfigJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public CatalogItem() {
    }

    public CatalogItem(User user, String name, BigDecimal netSellingUnitPrice, String unit) {
        this.user = user;
        this.name = name;
        this.netSellingUnitPrice = netSellingUnitPrice;
        this.unit = unit;
    }

}
