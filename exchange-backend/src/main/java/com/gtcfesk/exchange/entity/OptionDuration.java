package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "option_duration")
public class OptionDuration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer duration; // 时长（秒）

    @Column(nullable = false, length = 20)
    private String label; // 显示标签，如 "30s", "60s"

    @Column(nullable = false)
    private Integer sortOrder; // 排序顺序

    @Column(nullable = false)
    private Boolean enabled; // 是否启用

    @Column(name = "profit_rate", precision = 5, scale = 4, nullable = false)
    private java.math.BigDecimal profitRate; // 盈亏比例（如 0.8 表示 80%）

    @Column(name = "loss_rate", precision = 5, scale = 4, nullable = false)
    private java.math.BigDecimal lossRate; // 亏损比例（如 1.0 表示 100%，全部亏损）

    @Column(name = "min_amount", precision = 18, scale = 2)
    private java.math.BigDecimal minAmount; // 最低购买金额

    @Column(name = "max_amount", precision = 18, scale = 2)
    private java.math.BigDecimal maxAmount; // 最大购买金额

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

