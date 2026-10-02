package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;
import com.gtcfesk.exchange.common.ForexDisplayName;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@org.hibernate.annotations.Persister(impl = com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Entity
@Table(name = "option_order", uniqueConstraints = @UniqueConstraint(name="uk_option_order_request", columnNames={"tenant_id","user_id","request_key"}))
public class OptionOrder extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name="request_key", length=64, updatable=false)
    private String requestKey;
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name="request_hash", length=64, updatable=false)
    private String requestHash;


    // NULL is historical mixed funding. New orders always persist a single source.
    @Column(name="funding_source",length=16) private String fundingSource;
    @Column(name="trial_allocations",length=4000) private String trialAllocations;
    @Column(name="trial_reserved", precision=32, scale=16)
    private java.math.BigDecimal trialReserved;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by", length = 64)
    private String deletedBy;

    @Transient
    public boolean isDeleted() { return deletedAt != null; }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String symbol; // 交易对，如 BTCUSDT

    @Column(nullable = false, length = 10)
    private String direction; // UP 买涨, DOWN 买跌

    @Column(nullable = false, precision = 32, scale = 16)
    private BigDecimal amount; // 金额

    @Column(precision = 32, scale = 16)
    private BigDecimal openPrice; // 开仓价格

    @Column(precision = 32, scale = 16)
    private BigDecimal closePrice; // 平仓价格

    @Column(nullable = false, length = 20)
    private String status; // TRADING 交易中, CLOSED 已平仓, CANCELLED 已取消

    @Column(precision = 32, scale = 16)
    private BigDecimal profit; // 盈亏

    @Column(name = "duration")
    private Integer duration; // 时长（秒）

    @Column(name = "preset_profit_type", length = 10)
    private String presetProfitType; // 预设盈亏类型：PROFIT 盈利, LOSS 亏损, null 未设置

    @Column(name = "open_time")
    private LocalDateTime openTime; // 开仓时间

    @Column(name = "close_time")
    private LocalDateTime closeTime; // 平仓时间

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ==== 仅用于接口展示的临时字段（不入库） ====
    @Transient
    private String agentInfo; // 代理信息，格式：所属代理:用户名

    @Transient
    public String getDisplayName() {
        return ForexDisplayName.of(symbol);
    }

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (openTime == null && status.equals("TRADING")) {
            openTime = now;
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
        if (closeTime == null && status.equals("CLOSED")) {
            closeTime = LocalDateTime.now();
        }
    }
}

