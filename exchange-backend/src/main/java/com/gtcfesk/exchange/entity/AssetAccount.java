package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "asset_account")
public class AssetAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 这里的 coin 我们约定三种逻辑账户：
     * FUND  资金账户（充值/提现）
     * CONTRACT 合约资产
     * OPTION 期权资产（秒合约）
     */
    @Column(nullable = false, length = 32)
    private String coin;

    @Column(nullable = false, precision = 32, scale = 16)
    private BigDecimal available = BigDecimal.ZERO;

    @Column(nullable = false, precision = 32, scale = 16)
    private BigDecimal frozen = BigDecimal.ZERO;

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




