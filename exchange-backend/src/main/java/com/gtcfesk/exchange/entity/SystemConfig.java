package com.gtcfesk.exchange.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@org.hibernate.annotations.Persister(impl = com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Entity
@Table(name = "system_config")
public class SystemConfig extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, length = 100)
    private String configKey;

    @Column(name = "config_value", columnDefinition = "TEXT")
    private String configValue;

    @Column(length = 200)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();
}







