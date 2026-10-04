package com.gtcfesk.exchange.insights.traders;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Getter @Setter
@org.hibernate.annotations.Persister(impl=com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Table(name="trader_history",uniqueConstraints={@UniqueConstraint(columnNames={"tenant_id","environment","traderId","recordId"}),
    @UniqueConstraint(columnNames={"tenant_id","environment","traderId","recordKey"})},
    indexes=@Index(name="trader_closed",columnList="tenant_id,environment,traderId,closedAt"))
public class TraderHistory extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=4,updatable=false) private String environment;
    @Column(nullable=false,length=36,updatable=false) private String traderId, recordId;
    @Column(nullable=false) private Instant closedAt;
    @Column(nullable=false,length=40) private String symbol;
    @Column(nullable=false,length=8) private String direction;
    @Column(precision=38,scale=18) private BigDecimal leverage, quantity, pnl, fees;
    @Column(length=16) private String quantityUnit;
    @Column(nullable=false,length=16) private String pnlBasis;
    @Column(nullable=false,length=12) private String currency;
    @Column(length=1000) private String sourceNote, evidenceNote;
    @Column(nullable=false,length=80) private String recordKey;
}
