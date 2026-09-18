package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "loan_setting")
public class LoanSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer days; // 贷款期限（天数）

    @Column(name = "daily_rate", nullable = false, precision = 8, scale = 6)
    private BigDecimal dailyRate; // 日利率（百分比，如0.18表示0.18%）

    @Column(name = "free_days", nullable = false)
    private Integer freeDays = 0; // 免息天数

    @Column(name = "overdue_rate", precision = 8, scale = 6)
    private BigDecimal overdueRate; // 逾期费率（百分比，如0.25表示0.25%）

    @Column(name = "min_amount", precision = 32, scale = 16)
    private BigDecimal minAmount; // 最小金额

    @Column(name = "max_amount", precision = 32, scale = 16)
    private BigDecimal maxAmount; // 最大金额

    @Column(nullable = false)
    private Boolean enabled = true; // 是否启用

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



