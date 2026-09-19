package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "deposit_record")
public class DepositRecord {

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
    private BigDecimal amount; // 充值金额

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



