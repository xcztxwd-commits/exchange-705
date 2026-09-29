package com.gtcfesk.exchange.activity;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Getter @Setter @Entity @Table(name="activity_campaign")
public class ActivityCampaign {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Version private long rowVersion;
 @Column(nullable=false,length=120) private String name;
 @Column(nullable=false,length=16) private String status="DRAFT";
 private boolean template;
 private boolean autoPopup=true;
 @Column(nullable=false,length=16) private String animation="GIFT";
 @Column(nullable=false,length=16) private String defaultLocale="zh-CN";
 @Column(nullable=false,columnDefinition="LONGTEXT") private String translations="{}";
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
 public boolean active(){LocalDateTime now=LocalDateTime.now();return !template && "ACTIVE".equals(status) && (startsAt==null || !now.isBefore(startsAt)) && (endsAt==null || now.isBefore(endsAt));}
}
