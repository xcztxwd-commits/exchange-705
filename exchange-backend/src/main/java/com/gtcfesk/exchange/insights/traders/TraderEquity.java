package com.gtcfesk.exchange.insights.traders;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Getter @Setter
@org.hibernate.annotations.Persister(impl=com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Table(name="trader_equity",uniqueConstraints={@UniqueConstraint(columnNames={"tenant_id","environment","traderId","pointAt"}),
    @UniqueConstraint(columnNames={"tenant_id","environment","pointId"})})
public class TraderEquity extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=4,updatable=false) private String environment;
    @Column(nullable=false,length=36,updatable=false) private String traderId, pointId;
    @Column(nullable=false) private Instant pointAt;
    @Column(nullable=false,precision=38,scale=18) private BigDecimal netAsset;
    @Column(precision=38,scale=18) private BigDecimal cashFlow;
    @Column(nullable=false,length=12) private String currency;
    @Column(length=1000) private String sourceNote;
}
