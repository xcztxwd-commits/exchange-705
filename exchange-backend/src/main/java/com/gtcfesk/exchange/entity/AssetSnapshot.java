package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.math.BigDecimal;

/** Actual account equity samples; never reconstruct unobserved history. */
@Getter @Setter @org.hibernate.annotations.Persister(impl = com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Entity
@Table(name = "asset_snapshot", indexes = @Index(name = "idx_snapshot_user_time", columnList = "user_id,captured_at"))
public class AssetSnapshot extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "captured_at", nullable = false) private Long capturedAt;
    @Column(nullable = false, precision = 32, scale = 16) private BigDecimal total;
}
