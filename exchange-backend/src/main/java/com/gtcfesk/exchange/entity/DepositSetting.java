package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "deposit_setting")
public class DepositSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String type = "digital"; // 类型: digital 数字货币, bank 银行卡

    @Column(length = 50)
    private String network; // 网络/币种，如 USDC-ERC20, USDT-TRC20（银行卡时可为空）

    @Column(length = 200)
    private String address; // 充值地址（银行卡时可为空）

    @Column(name = "qr_code", length = 500)
    private String qrCode; // 二维码图片URL

    @Column(name = "bank_name", length = 100)
    private String bankName; // 开户银行

    @Column(name = "bank_account", length = 50)
    private String bankAccount; // 银行卡号

    @Column(name = "account_name", length = 100)
    private String accountName; // 户名

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

