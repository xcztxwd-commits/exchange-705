package com.gtcfesk.exchange.control;
import javax.persistence.*;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Entity
@Table(name="tenant")
public class Tenant {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,unique=true,length=64) private String code;
 @Column(nullable=false,length=128) private String name;
 @Column(name="frontend_host",unique=true,length=253) private String frontendHost;
 @Column(nullable=false,length=32) private String status="DRAFT";
 @Column(nullable=false,length=32) private String templateVersion="safe-v1";
 @Column(nullable=false) private long policyVersion;
 @Column(nullable=false) private long sessionVersion;
 @Column(nullable=false) private boolean configReady;
 @Column(nullable=false) private boolean domainVerified;
 @Version private long rowVersion;
 private LocalDateTime createdAt=LocalDateTime.now();
}
