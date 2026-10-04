package com.gtcfesk.exchange.insights;
import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
@Entity @Getter @Setter
@org.hibernate.annotations.Persister(impl=com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Table(name="calendar_audit",indexes=@Index(name="calendar_audit_event",columnList="tenant_id,environment,eventId,id"))
public class CalendarAudit extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=4) private String environment;
    @Column(nullable=false,length=36) private String eventId;
    @Column(nullable=false,length=100) private String actor;
    @Column(nullable=false,length=40) private String action;
    @Column(nullable=false,length=1000) private String reason;
    @Lob private String beforeJson;
    @Lob private String afterJson;
    @Column(nullable=false) private Instant createdAt=Instant.now();
}
