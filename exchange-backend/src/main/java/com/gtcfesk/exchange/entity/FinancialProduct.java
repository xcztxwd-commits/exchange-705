package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "financial_product")
public class FinancialProduct {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name; // 产品名称（如180MH/S）

    @Column(name = "image_url", length = 500)
    private String imageUrl; // 产品图片

    @Column(nullable = false, length = 10)
    private String currency = "USD"; // 货币类型

    @Column(name = "daily_yield_rate", nullable = false, precision = 8, scale = 6)
    private BigDecimal dailyYieldRate; // 预计日产率（百分比，如0.3表示0.3%）

    @Column(name = "rental_fee", nullable = false, precision = 32, scale = 16)
    private BigDecimal rentalFee; // 矿机租金

    @Column(name = "min_purchase", nullable = false, precision = 32, scale = 16)
    private BigDecimal minPurchase; // 最小申购金额

    @Column(name = "max_purchase", nullable = false, precision = 32, scale = 16)
    private BigDecimal maxPurchase; // 最大申购金额

    @Column(name = "term_days", nullable = false)
    private Integer termDays; // 理财期限（天数）

    @Column(name = "penalty_rate", precision = 8, scale = 6)
    private BigDecimal penaltyRate = new BigDecimal("0.3"); // 违约赎回费率（百分比，如30表示30%）

    @Column(columnDefinition = "TEXT")
    private String description; // 产品介绍

    @Column(nullable = false)
    private Boolean enabled = true; // 是否启用

    @Column(name = "sort_order")
    private Integer sortOrder = 0; // 排序

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}



