package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "deposit_record", uniqueConstraints = {
    @UniqueConstraint(name="uk_deposit_order_no", columnNames="order_no"),
    @UniqueConstraint(name="uk_deposit_request", columnNames={"created_by_type","created_by_id","idempotency_key"})})
public class DepositRecord {

    @Column(name="order_no", length=64)
    private String orderNo;
    @Column(name="source", length=32)
    private String source;
    @Column(name="account_type", length=16)
    private String accountType;
    @Column(name="manual_purpose", length=24)
    private String manualPurpose;
    @Column(name="idempotency_key", length=64)
    private String idempotencyKey;
    @Column(name="request_hash", length=64)
    private String requestHash;
    @Column(name="review_remark", length=500)
    private String reviewRemark;
    @Column(name="created_by_type", length=24)
    private String createdByType;
    @Column(name="created_by_id")
    private Long createdById;
    @Column(name="created_by_name", length=128)
    private String createdByName;
    @Column(name="reviewed_by_type", length=24)
    private String reviewedByType;
    @Column(name="reviewed_by_id")
    private Long reviewedById;
    @Column(name="reviewed_by_name", length=128)
    private String reviewedByName;
    @Column(name="fee_rate", precision=32, scale=16)
    private BigDecimal feeRate;
    @Column(name="fee_amount", precision=32, scale=16)
    private BigDecimal feeAmount;
    private LocalDateTime reviewedAt;
    private LocalDateTime creditedAt;

    @Transient
    private boolean idempotentReplay;

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
    private String network; // 网络/币种

    @Column(nullable = false, precision = 32, scale = 16)
    private BigDecimal amount; // USD 入账金额；旧记录保持 USD 语义

    @Column(length = 3)
    private String currency;

    @Column(name = "original_amount", precision = 32, scale = 16)
    private BigDecimal originalAmount;

    @Column(name = "exchange_rate", precision = 32, scale = 16)
    private BigDecimal exchangeRate;

    @Column(nullable = false, length = 200)
    private String address; // 充值地址

    @Column(name = "proof_image", length = 500)
    private String proofImage; // 凭证图片URL

    @Column(nullable = false, length = 20)
    private String status = "PENDING"; // PENDING 未审核, COMPLETED 已完成, REJECTED 已拒绝

    @Column(length = 500)
    private String remark; // 备注

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



