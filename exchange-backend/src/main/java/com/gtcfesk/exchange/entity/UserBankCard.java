package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "user_bank_card")
public class UserBankCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 10)
    private String currency; // 货币代码，如 USD, EUR, GBP

    @Column(name = "bank_name", nullable = false, length = 100)
    private String bankName; // 银行名称

    @Column(name = "bank_address", length = 200)
    private String bankAddress; // 银行地址

    @Column(length = 50)
    private String swift; // SWIFT代码

    @Column(name = "recipient_name", nullable = false, length = 100)
    private String recipientName; // 收款人姓名

    @Column(name = "recipient_account", nullable = false, length = 100)
    private String recipientAccount; // 收款人账户

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



