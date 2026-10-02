package com.gtcfesk.exchange.control;
import javax.persistence.*;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Entity
@Table(name="backend_login",uniqueConstraints={@UniqueConstraint(columnNames={"tenant_id","admin_user_id"}),@UniqueConstraint(columnNames={"tenant_id","user_id"})})
public class BackendLogin {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,unique=true,length=128) private String normalizedAccount;
 @Column(name="tenant_id",nullable=false) private Long tenantId;
 @Column(nullable=false,length=16) private String subjectType;
 @Column(name="admin_user_id") private Long adminUserId;
 @Column(name="user_id") private Long userId;
 @Column(nullable=false) private boolean enabled=true;
 public Long subjectId(){return "ADMIN".equals(subjectType)?adminUserId:userId;}
}
