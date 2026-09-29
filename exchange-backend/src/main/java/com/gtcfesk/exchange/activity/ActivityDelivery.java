package com.gtcfesk.exchange.activity;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.LocalDateTime;
@Getter @Setter @Entity
@Table(name="activity_delivery",uniqueConstraints=@UniqueConstraint(name="uk_activity_recipient",columnNames={"campaignId","userId"}),indexes=@Index(name="ix_activity_user",columnList="userId,id"))
public class ActivityDelivery {
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
