package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "contract_order")
public class ContractOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
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

