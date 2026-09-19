package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "transfer_record", uniqueConstraints = @UniqueConstraint(name = "uk_transfer_request", columnNames = {"user_id", "request_id"}))
public class TransferRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "from_account", nullable = false, length = 32)
    private String fromAccount; // FUND, CONTRACT, OPTION

    @Column(name = "to_account", nullable = false, length = 32)
    private String toAccount; // FUND, CONTRACT, OPTION

    @Column(name = "amount", nullable = false, precision = 32, scale = 16)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }
}



