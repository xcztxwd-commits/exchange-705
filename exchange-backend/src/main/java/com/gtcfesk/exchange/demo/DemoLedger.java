package com.gtcfesk.exchange.demo;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Append-only virtual cash movements. Never read by real account reporting. */
@Entity
@Table(name = "demo_ledger", indexes = @Index(name = "idx_demo_ledger_owner_time", columnList = "user_id,created_at"))
public class DemoLedger {
    @Id @Column(length = 36) public String id;
    @Column(name = "user_id", nullable = false) public Long userId;
    @Column(nullable = false) public int generation;
    @Column(nullable = false, length = 16) public String type;
    @Column(length = 36) public String orderId;
    @Column(nullable = false, precision = 32, scale = 8) public BigDecimal delta;
    @Column(nullable = false, precision = 32, scale = 8) public BigDecimal balanceAfter;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
}
