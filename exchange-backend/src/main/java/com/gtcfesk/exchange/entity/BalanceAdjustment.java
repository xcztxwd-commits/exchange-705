package com.gtcfesk.exchange.entity;
import com.gtcfesk.exchange.tenant.TenantOwnedEntity;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.LocalDateTime;
@org.hibernate.annotations.Persister(impl = com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Entity @Getter @Setter @Table(name="balance_adjustment",uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","request_key"}))
public class BalanceAdjustment extends TenantOwnedEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,updatable=false) private Long userId;
 @Column(name="request_key",nullable=false,updatable=false,length=64) private String requestKey;
 @Column(nullable=false,updatable=false,length=64) private String requestHash;
 @Column(updatable=false,length=16) private String actorType;
 @Column(updatable=false) private Long actorId;
 @Column(updatable=false,length=500) private String reason;
 @Column(updatable=false,columnDefinition="TEXT") private String changes;
 @Column(nullable=false,updatable=false) private LocalDateTime createdAt=LocalDateTime.now();
}
