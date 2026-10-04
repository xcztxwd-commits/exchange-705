package com.gtcfesk.exchange.insights.news;
import lombok.Getter;import lombok.Setter;import javax.persistence.*;import java.time.Instant;
@Entity @Getter @Setter
@org.hibernate.annotations.Persister(impl=com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Table(name="news_article",uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","environment","articleId"}),indexes=@Index(name="news_window",columnList="tenant_id,environment,sourceId,hidden,publishedAt"))
public class NewsArticle extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=4,updatable=false) private String environment;
    @Column(nullable=false,length=36,updatable=false) private String articleId;
    @Column(nullable=false,length=24,updatable=false) private String sourceId;
    @Column(nullable=false,length=16) private String category,language;
    private Instant publishedAt;
    @Column(nullable=false) private Instant discoveredAt,updatedAt;
    private boolean hidden;
    private int sortOrder;
    @Lob @Column(nullable=false) private String dataJson;
    @Column(nullable=false,length=64) private String upstreamHash;
    @Version private long rowVersion;
}
