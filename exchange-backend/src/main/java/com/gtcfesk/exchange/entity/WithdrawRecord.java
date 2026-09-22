package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "withdraw_record")
public class WithdrawRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String type; // digital 数字货币, bank 银行卡

    @Column(nullable = false, length = 50)
    private String network; // 网络/币种 (如 USDT-TRC20, USD)

    @Column(nullable = false, precision = 32, scale = 16)
    private BigDecimal amount; // USD 账本金额

    @Column(length = 3)
    private String currency; // 输入币种；历史记录为空

    @Column(name = "original_amount", precision = 32, scale = 16)
    private BigDecimal originalAmount;

    @Column(name = "exchange_rate", precision = 32, scale = 16)
    private BigDecimal exchangeRate;

    @Column(name = "actual_amount", precision = 32, scale = 16)
    private BigDecimal actualAmount; // 实际到账金额（扣除手续费后）

    @Column(name = "fee", precision = 32, scale = 16)
    private BigDecimal fee = BigDecimal.ZERO; // 手续费

    @Column(nullable = false, length = 200)
    private String address; // 提币地址/收款账户

    @Column(length = 500)
    private String remark; // 备注

    @Column(nullable = false, length = 20)
    private String status = "PENDING"; // PENDING 待审核, APPROVED 已通过, REJECTED 已驳回, COMPLETED 已完成

    @Column(name = "review_remark", length = 500)
    private String reviewRemark; // 审核备注

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt; // 审核时间

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
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}



