package com.gtcfesk.exchange.entity;

import lombok.Data;
import javax.persistence.*;

@Data
@org.hibernate.annotations.Persister(impl = com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Entity
@Table(name = "admin_table_preference")
public class AdminTablePreference extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id
    @Column(length = 200)
    private String id;
    @Lob
    @Column(nullable = false)
    private String columnsJson;
}
