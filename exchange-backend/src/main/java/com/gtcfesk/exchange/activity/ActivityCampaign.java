package com.gtcfesk.exchange.activity;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Getter @Setter @org.hibernate.annotations.Persister(impl = com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Entity @Table(name="activity_campaign")
public class ActivityCampaign extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Version private long rowVersion;
 @Column(nullable=false,length=120) private String name;
 @Column(nullable=false,length=16) private String status="DRAFT";
 private boolean template;
 private boolean autoPopup=true;
 private boolean autoSendEnabled;
 private boolean repeatUnread;
 private boolean allowRepeatSend;
 private boolean allowRepeatClaim;
 private Integer claimValidityDays;
 @Convert(converter=ActivityStringListConverter.class) @Column(name="positions",nullable=false,length=500) private java.util.List<String> positions=new java.util.ArrayList<>(java.util.Collections.singletonList("AUTH_HOME"));
 @Convert(converter=ActivityStringListConverter.class) @Column(name="trigger_conditions",nullable=false,length=500) private java.util.List<String> triggerConditions=new java.util.ArrayList<>();
 private boolean deleted;
 @Column(nullable=false,length=16) private String animation="GIFT";
 @Column(nullable=false,length=16) private String defaultLocale="zh-CN";
 @Column(nullable=false,columnDefinition="LONGTEXT") private String translations="{}";
 @Column(columnDefinition="LONGTEXT") private String layoutJson;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal amount=new BigDecimal("300");
 private int recentLoginDays=3;
 private int maxClaims=1000;
 private int claimCount;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal budget=new BigDecimal("300000");
 @Column(nullable=false,precision=32,scale=16) private BigDecimal granted=BigDecimal.ZERO;
 private LocalDateTime startsAt;
 private LocalDateTime endsAt;
 private LocalDateTime createdAt=LocalDateTime.now();
 private LocalDateTime updatedAt=LocalDateTime.now();
 @PreUpdate void update(){updatedAt=LocalDateTime.now();}
 public boolean active(){return active(LocalDateTime.now());}
 public boolean active(LocalDateTime now){return !deleted && !template && "ACTIVE".equals(status) && (startsAt==null || !now.isBefore(startsAt)) && (endsAt==null || now.isBefore(endsAt));}
}
