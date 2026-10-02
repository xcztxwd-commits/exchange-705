package com.gtcfesk.exchange.activity;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.LocalDateTime;
@Getter @Setter @org.hibernate.annotations.Persister(impl = com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Entity
@Table(name="activity_delivery",uniqueConstraints=@UniqueConstraint(name="uk_activity_recipient",columnNames={"tenant_id", "campaignId","userId"}),indexes=@Index(name="ix_activity_user",columnList="userId,id"))
public class ActivityDelivery extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Version private long rowVersion;
 @Column(nullable=false) private Long campaignId;
 @Column(nullable=false) private Long userId;
 private LocalDateTime sentAt=LocalDateTime.now();
 private LocalDateTime receivedAt;
 private LocalDateTime openedAt;
 private LocalDateTime closedAt;
 private LocalDateTime claimedAt;
 private int openCount;
 private int closeCount;
 @com.fasterxml.jackson.annotation.JsonIgnore
 @Column(length=120) private String sentBy;
}
