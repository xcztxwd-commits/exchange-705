package com.gtcfesk.exchange.activity;

import com.gtcfesk.exchange.tenant.TenantOwnedEntity;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Each claim owns its own clock and principal. Legacy balances use an undated grant. */
@Getter @Setter @Entity @org.hibernate.annotations.Persister(impl=com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Table(name="trial_grant", uniqueConstraints=@UniqueConstraint(name="uk_trial_grant_request",columnNames={"tenant_id","user_id","request_key"}),
 indexes={@Index(name="ix_trial_grant_user",columnList="tenant_id,user_id,id"),@Index(name="ix_trial_grant_campaign",columnList="tenant_id,campaign_id,user_id")})
public class TrialGrant extends TenantOwnedEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Version private long rowVersion;
 @Column(name="user_id",nullable=false) private Long userId;
 @Column(name="campaign_id") private Long campaignId;
 @Column(name="delivery_id") private Long deliveryId;
 @com.fasterxml.jackson.annotation.JsonIgnore @Column(name="request_key",nullable=false,length=80) private String requestKey;
 @Column(name="claimed_at",nullable=false) private LocalDateTime claimedAt;
 @Column(name="expires_at") private LocalDateTime expiresAt;
 @Column(nullable=false) private boolean active=true;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal available=BigDecimal.ZERO;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal frozen=BigDecimal.ZERO;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal consumed=BigDecimal.ZERO;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal expired=BigDecimal.ZERO;
}
