package com.gtcfesk.exchange.control;
import javax.persistence.*;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Entity
@Table(name="tenant_domain_history")
public class TenantDomainHistory {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false) private Long tenantId;
 @Column(name="domain_role",nullable=false,length=16) private String role="FRONTEND";
 @Column(nullable=false,unique=true,length=253) private String hostname;
 @Column(nullable=false) private LocalDateTime retiredAt=LocalDateTime.now();
}
