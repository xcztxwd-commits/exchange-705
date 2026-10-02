package com.gtcfesk.exchange.activity;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Getter @Setter @org.hibernate.annotations.Persister(impl = com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Entity @Table(name="trial_ledger",indexes=@Index(name="ix_trial_ledger_user",columnList="userId,id"))
public class TrialLedger extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false) private Long userId;
 @Column(nullable=false,length=100) private String reason;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal available;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal frozen;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal delta;
 private LocalDateTime createdAt=LocalDateTime.now();
}
