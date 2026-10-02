package com.gtcfesk.exchange.control;
import javax.persistence.*;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Entity
@Table(name="tenant_policy",uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","policy_key"}))
public class TenantPolicy {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="tenant_id",nullable=false) private Long tenantId;
 @Column(name="policy_key",nullable=false,length=128) private String key;
 @Column(name="policy_value",columnDefinition="TEXT") private String value;
 @Column(nullable=false) private boolean locked;
 @Version private long version;
}
