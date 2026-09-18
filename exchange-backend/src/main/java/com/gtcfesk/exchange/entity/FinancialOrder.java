package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "financial_order")
public class FinancialOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName; // 产品名称

    @Column(name = "purchase_amount", nullable = false, precision = 32, scale = 16)
    private BigDecimal purchaseAmount; // 申购数量（金额）

    @Column(nullable = false, length = 10)
    private String currency = "USD"; // 货币类型

    @Column(name = "daily_yield_rate", nullable = false, precision = 8, scale = 6)
    private BigDecimal dailyYieldRate; // 日产率

    @Column(name = "daily_yield", nullable = false, precision = 32, scale = 16)
    private BigDecimal dailyYield; // 预计日产（金额）

    @Column(name = "total_yield", nullable = false, precision = 32, scale = 16)
    private BigDecimal totalYield; // 预计总收益

    @Column(name = "term_days", nullable = false)
    private Integer termDays; // 理财期限（天数）

    @Column(name = "penalty_rate", precision = 8, scale = 6)
    private BigDecimal penaltyRate = new BigDecimal("0.3"); // 违约赎回费率

    @Column(name = "penalty_amount", precision = 32, scale = 16)
    private BigDecimal penaltyAmount = BigDecimal.ZERO; // 违约金金额

    @Column(nullable = false, length = 20)
    private String status = "IN_PROGRESS"; // IN_PROGRESS进行中, COMPLETED已结束, REDEEMED已赎回

    @Column(name = "purchase_time", nullable = false)
    private LocalDateTime purchaseTime; // 申购时间

    @Column(name = "end_time")
    private LocalDateTime endTime; // 结束时间

    @Column(name = "redeem_time")
    private LocalDateTime redeemTime; // 赎回时间

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (purchaseTime == null) {
            purchaseTime = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}



