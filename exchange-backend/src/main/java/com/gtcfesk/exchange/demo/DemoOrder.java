package com.gtcfesk.exchange.demo;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@org.hibernate.annotations.Persister(impl = com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Entity
@Table(name = "demo_order", uniqueConstraints = @UniqueConstraint(columnNames={"tenant_id", "user_id", "request_key"}),
    indexes = @Index(name = "idx_demo_order_owner_status", columnList = "user_id,status,created_at"))
public class DemoOrder extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id @Column(length = 36) public String id;
    @Column(name = "user_id", nullable = false) public Long userId;
    @Column(name = "request_key", nullable = false, length = 36) public String requestKey;
    @Column(nullable = false) public int generation;
    @Column(nullable = false, length = 32) public String symbol;
    @Column(nullable = false, length = 64) public String marketCode;
    @Column(nullable = false, length = 16) public String status;
    @Column(nullable = false, precision = 32, scale = 8) public BigDecimal amount;
    @Column(nullable = false, precision = 32, scale = 16) public BigDecimal quantity;
    @Column(nullable = false, precision = 32, scale = 16) public BigDecimal openPrice;
    @Column(precision = 32, scale = 16) public BigDecimal closePrice;
    @Column(nullable = false, precision = 32, scale = 8) public BigDecimal openFee;
    @Column(precision = 32, scale = 8) public BigDecimal closeFee;
    @Column(precision = 32, scale = 8) public BigDecimal realizedPnl;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    public Instant closedAt;
}
