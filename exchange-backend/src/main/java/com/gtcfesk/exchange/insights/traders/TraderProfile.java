package com.gtcfesk.exchange.insights.traders;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.Instant;

@Entity @Getter @Setter
@org.hibernate.annotations.Persister(impl=com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Table(name="trader_profile",uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","environment","traderId"}),
    indexes=@Index(name="trader_public",columnList="tenant_id,environment,status,recommended,sortOrder"))
public class TraderProfile extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=4,updatable=false) private String environment;
    @Column(nullable=false,length=36,updatable=false) private String traderId;
    @Column(nullable=false,length=16) private String sourceType, status;
    @Column(nullable=false,length=80) private String name;
    @Column(length=300) private String avatarUrl;
    @Column(nullable=false,length=12) private String currency;
    private boolean recommended;
    private int sortOrder;
    @Column(nullable=false) private Instant updatedAt;
    @Lob @Column(nullable=false) private String dataJson;
    @Version private long rowVersion;
}
