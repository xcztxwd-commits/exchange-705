package com.gtcfesk.exchange.control;
import javax.persistence.*;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Entity
@Table(name="control_audit_log")
@org.hibernate.annotations.Immutable
public class ControlAuditLog {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 private Long actorId;
 private Long tenantId;
 @Column(length=64) private String accessSessionId;
 @Column(nullable=false,length=64) private String requestId;
 @Column(nullable=false,length=128) private String action;
 @Column(length=255) private String objectRef;
 @Column(nullable=false,length=32) private String outcome;
 @Column(columnDefinition="TEXT") private String detail;
 @Column(length=512) private String reason;
 @Column(length=64) private String remoteAddress;
 @Column(nullable=false,updatable=false) private LocalDateTime createdAt=LocalDateTime.now();
}
