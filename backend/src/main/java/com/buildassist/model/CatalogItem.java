package com.buildassist.model;

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
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private String unit;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column
    @Enumerated(jakarta.persistence.EnumType.STRING)
    private CalculationStrategy calculationStrategy = CalculationStrategy.DIRECT;

    public CatalogItem() {
    }

    public CatalogItem(User user, String name, BigDecimal unitPrice, String unit) {
        this.user = user;
        this.name = name;
        this.unitPrice = unitPrice;
        this.unit = unit;
    }

}
