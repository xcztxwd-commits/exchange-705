package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "trading_symbol")
public class TradingSymbol {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String symbol; // BTCUSD, XAUUSD

    @Column(name = "base_currency", nullable = false, length = 16)
    private String baseCurrency; // BTC, XAU

    @Column(name = "quote_currency", nullable = false, length = 16)
    private String quoteCurrency = "USD"; // USD

    @Column(nullable = false, length = 64)
    private String name; // 比特币/美元

    @Column(name = "name_en", length = 64)
    private String nameEn; // Bitcoin/USD

    @Column(nullable = false, length = 32)
    private String category = "US"; // US, Crypto, Metal, Forex, CFD, Oil

    @Column(name = "icon_url", length = 255)
    private String iconUrl;

    @Column(name = "flag_url", length = 255)
    private String flagUrl;

    @Column(name = "is_hot", nullable = false)
    private Boolean isHot = false;

    @Column(name = "is_enabled", nullable = false)
    private Boolean isEnabled = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "price_precision", nullable = false)
    private Integer pricePrecision = 2;

    @Column(name = "volume_precision", nullable = false)
    private Integer volumePrecision = 2;

    @Column(name = "min_trade_amount", precision = 32, scale = 16)
    private BigDecimal minTradeAmount = BigDecimal.ZERO;

    @Column(name = "alltick_symbol", length = 64)
    private String alltickSymbol; // Alltick API中的symbol

    @Column(name = "current_price", precision = 32, scale = 16)
    private BigDecimal currentPrice = BigDecimal.ZERO;

    @Column(name = "price_change_24h", precision = 32, scale = 16)
    private BigDecimal priceChange24h = BigDecimal.ZERO;

    @Column(name = "price_change_pct_24h", precision = 10, scale = 4)
    private BigDecimal priceChangePct24h = BigDecimal.ZERO;

    @Column(name = "sparkline_data", columnDefinition = "TEXT")
    private String sparklineData; // JSON数组，用于K线小图

    // ====== AI 控盘相关字段 ======
    /**
     * 是否启用控盘价格。
     * 启用后，行情价格与K线会在真实价格基础上按 controlPriceOffset 做平移。
     */
    @Column(name = "control_enabled", nullable = false)
    private Boolean controlEnabled = false;

    /**
     * 控盘价格偏移量（与报价货币同单位，例如 USDT）。
     * 最终展示价格 = 实际市场价格 + controlPriceOffset。
     */
    @Column(name = "control_price_offset", precision = 32, scale = 16)
    private BigDecimal controlPriceOffset = BigDecimal.ZERO;

    @JsonIgnore
    @Column(name = "control_start_price", precision = 32, scale = 16)
    private BigDecimal controlStartPrice;

    @JsonIgnore
    @Column(name = "control_target_price", precision = 32, scale = 16)
    private BigDecimal controlTargetPrice;

    @JsonIgnore
    @Column(name = "control_started_at")
    private Long controlStartedAt;

    @JsonIgnore
    @Column(name = "control_duration_seconds")
    private Integer controlDurationSeconds;

    @JsonIgnore
    @Column(name = "control_intensity")
    private Integer controlIntensity;

    @JsonIgnore
    @Column(name = "control_random_oscillation")
    private Boolean controlRandomOscillation;

    @JsonIgnore
    @Column(name = "control_completed_at")
    private Long controlCompletedAt;

    @JsonIgnore
    @Column(name = "control_restoring")
    private Boolean controlRestoring;

    @JsonIgnore
    @Version
    @Column(name = "row_version", nullable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 0")
    private long rowVersion;

    // 合约交易设置
    @Column(name = "lot_size", precision = 32, scale = 16)
    private BigDecimal lotSize = BigDecimal.valueOf(1000); // 每手数量，默认1000

    @Column(name = "fee_multiplier", precision = 32, scale = 16)
    private BigDecimal feeMultiplier = BigDecimal.valueOf(30); // 手续费倍数，默认30

    @Column(name = "leverage", precision = 10, scale = 2)
    private BigDecimal leverage = BigDecimal.valueOf(10); // 杠杆倍数，默认10倍

    @Column(name = "max_leverage", precision = 10, scale = 2)
    private BigDecimal maxLeverage; // 新订单可选杠杆上限；NULL 表示100倍，旧leverage字段保留兼容

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
