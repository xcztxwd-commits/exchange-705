package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "financial_yield_record", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"order_id", "yield_date"})
})
public class FinancialYieldRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    @Column(name = "yield_date", nullable = false)
    private LocalDate yieldDate; // 收益日期

    @Column(name = "daily_yield", nullable = false, precision = 32, scale = 16)
    private BigDecimal dailyYield; // 当日收益

    @Column(name = "cumulative_yield", nullable = false, precision = 32, scale = 16)
    private BigDecimal cumulativeYield; // 累计收益

    @Column(nullable = false, length = 20)
    private String status = "PENDING"; // PENDING待发放, PAID已发放

    @Column(name = "paid_at")
    private LocalDateTime paidAt; // 发放时间

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



