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
@Table(name = "contract_order", uniqueConstraints = @UniqueConstraint(name="uk_contract_order_request", columnNames={"tenant_id","user_id","request_key"}))
public class ContractOrder extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
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


    // NULL denotes the legacy lot protocol. Never infer an old order's unit from today's symbol.
    @Column(name = "quantity_unit_type", length = 16)
    private String quantityUnitType;
    @Column(name = "spec_version")
    private Long specVersion;
    @Column(name = "min_order_quantity", precision = 32, scale = 16)
    private BigDecimal minOrderQuantity;
    @Column(name = "quantity_step", precision = 32, scale = 16)
    private BigDecimal quantityStep;
    @Column(name = "min_order_notional", precision = 32, scale = 16)
    private BigDecimal minOrderNotional;

    @Column(name = "quantity_asset", length = 16)
    private String quantityAsset;

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

    @Column(name = "order_source", nullable = false, length = 24)
    private String orderSource = "USER";
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "manual_wallet_enabled", nullable = false)
    private boolean manualWalletEnabled;
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "manual_equity_enabled", nullable = false)
    private boolean manualEquityEnabled;

    // Manual timestamps are stored in UTC; legacy local timestamp semantics stay unchanged.
    @Transient
    public Long getManualOpenTimeUtc() {
        return "MANUAL_TEST".equals(orderSource) && openTime != null ? openTime.toInstant(java.time.ZoneOffset.UTC).toEpochMilli() : null;
    }
    @Transient
    public Long getManualCloseTimeUtc() {
        return "MANUAL_TEST".equals(orderSource) && closeTime != null ? closeTime.toInstant(java.time.ZoneOffset.UTC).toEpochMilli() : null;
    }

    // Existing pending orders stay excluded until their historical handling is explicitly decided.
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "limit_match_enabled", nullable = false)
    private boolean limitMatchEnabled;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 32)
    private String symbol; // 交易对，如 BTCUSDT

    @Column(nullable = false, length = 10)
    private String side; // BUY 买入, SELL 卖出

    @Column(nullable = false, length = 10)
    private String type; // MARKET 市价, LIMIT 限价

    @Column(nullable = false, precision = 32, scale = 16)
    private BigDecimal quantity; // 数量

    @Column(precision = 32, scale = 16)
    private BigDecimal price; // 挂单价格（限价单）

    @Column(precision = 32, scale = 16)
    private BigDecimal openPrice; // 开仓价格

    @Column(precision = 32, scale = 16)
    private BigDecimal currentPrice; // 当前价格

    @Column(precision = 32, scale = 16)
    private BigDecimal stopLoss; // 止损价格

    @Column(precision = 32, scale = 16)
    private BigDecimal takeProfit; // 止盈价格

    @Column(nullable = false, length = 20)
    private String status; // PENDING 挂单中, OPEN 持仓中, CLOSED 已平仓, CANCELLED 已取消

    @Column(precision = 32, scale = 16)
    private BigDecimal profit; // 盈亏

    @Column(name = "margin", precision = 32, scale = 16)
    private BigDecimal margin; // 保证金

    @Column(name = "fee", precision = 32, scale = 16)
    private BigDecimal fee; // 手续费

    @Column(name = "leverage", precision = 10, scale = 2)
    private BigDecimal leverage; // 杠杆倍数

    // 新订单保存每手数量快照；NULL 表示沿用历史保证金、盈亏和手续费规则。
    @Column(name = "lot_size", precision = 32, scale = 16)
    private BigDecimal lotSize;

    // NULL preserves every historical/pending order's original margin and fee interpretation.
    @Column(name = "fx_base_currency", length = 3)
    private String fxBaseCurrency;

    @Transient
    public BigDecimal getOpenCommission() { return fxBaseCurrency == null || fee == null ? null : fee.divide(BigDecimal.valueOf(2)); }
    @Transient
    public BigDecimal getCloseCommission() { return getOpenCommission(); }

    @Column(name = "quote_currency", length = 16)
    private String quoteCurrency = "USD";
    @Column(name = "quote_source", length = 16)
    private String quoteSource;
    @Column(name = "margin_conversion_rate", precision = 32, scale = 16)
    private BigDecimal marginConversionRate;
    @Column(name = "settlement_conversion_rate", precision = 32, scale = 16)
    private BigDecimal settlementConversionRate;

    @Column(name = "open_time")
    private LocalDateTime openTime; // 开仓时间

    @Column(name = "close_price", precision = 32, scale = 16)
    private BigDecimal closePrice; // 平仓价格

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
        if (openTime == null && status.equals("OPEN")) {
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

