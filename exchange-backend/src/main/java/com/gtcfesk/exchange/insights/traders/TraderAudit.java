package com.gtcfesk.exchange.insights.traders;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.Instant;

@Entity @Getter @Setter
@org.hibernate.annotations.Persister(impl=com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Table(name="trader_audit",indexes=@Index(name="trader_audit_scope",columnList="tenant_id,environment,id"))
public class TraderAudit extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=4) private String environment;
    @Column(nullable=false,length=36) private String traderId;
    @Column(length=36) private String objectId;
    @Column(nullable=false,length=100) private String actor;
    @Column(nullable=false,length=32) private String action;
    @Column(nullable=false,length=1000) private String reason;
    @Lob private String beforeJson, afterJson;
    @Column(nullable=false) private Instant capturedAt=Instant.now();
}
