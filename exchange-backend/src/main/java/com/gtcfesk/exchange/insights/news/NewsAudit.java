package com.gtcfesk.exchange.insights.news;
import lombok.Getter;import lombok.Setter;import javax.persistence.*;import java.time.Instant;
@Entity @Getter @Setter
@org.hibernate.annotations.Persister(impl=com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Table(name="news_audit",indexes=@Index(name="news_audit_scope",columnList="tenant_id,environment,id"))
public class NewsAudit extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=4) private String environment;
    @Column(length=36) private String articleId;
    @Column(nullable=false,length=24) private String sourceId;
    @Column(nullable=false,length=100) private String actor;
    @Column(nullable=false,length=24) private String action;
    @Column(nullable=false,length=1000) private String reason;
    @Lob private String beforeJson,afterJson;
    @Column(nullable=false) private Instant capturedAt=Instant.now();
}
