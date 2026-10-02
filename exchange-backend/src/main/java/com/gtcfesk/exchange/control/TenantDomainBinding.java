package com.gtcfesk.exchange.control;
import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
@Getter @Setter @Entity @Table(name="tenant_domain_binding")
public class TenantDomainBinding {
 @Id @Column(length=253) private String hostname;
 @Column(nullable=false) private Long tenantId;
 @Column(nullable=false,length=16) private String status;
 @JsonIgnore @Column(length=32) private String challenge;
 private Instant expiresAt,verifiedAt;
 @Version private long version;
}
