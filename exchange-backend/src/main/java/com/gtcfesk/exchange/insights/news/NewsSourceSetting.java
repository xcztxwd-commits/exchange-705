package com.gtcfesk.exchange.insights.news;
import lombok.Getter;import lombok.Setter;import javax.persistence.*;
@Entity @Getter @Setter
@org.hibernate.annotations.Persister(impl=com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Table(name="news_source_setting",uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","environment","sourceId"}))
public class NewsSourceSetting extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=4,updatable=false) private String environment;
    @Column(nullable=false,length=24,updatable=false) private String sourceId;
    private boolean enabled,licenseReviewed;
    @Column(length=1000) private String licenseEvidence;
    @Version private long rowVersion;
}
