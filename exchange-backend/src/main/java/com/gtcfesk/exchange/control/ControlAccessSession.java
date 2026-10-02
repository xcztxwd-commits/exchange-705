package com.gtcfesk.exchange.control;
import javax.persistence.*;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Entity
@Table(name="control_access_session")
public class ControlAccessSession {
 @Id @Column(length=64) private String id;
 @Column(nullable=false) private Long actorId;
 @Column(nullable=false) private Long tenantId;
 @JsonIgnore @Column(nullable=false,unique=true,length=64) private String ticketHash;
 @JsonIgnore @Column(nullable=false,length=64) private String browserBindingHash;
 @JsonIgnore @Column(nullable=false) private long actorVersion;
 @Column(nullable=false) private LocalDateTime expiresAt;
 @Column(nullable=false) private LocalDateTime ticketExpiresAt;
 @Column(nullable=false) private LocalDateTime lastActivityAt;
 @Column(nullable=false) private boolean consumed;
 @Column(nullable=false) private boolean revoked;
 @Version private long rowVersion;
 @Column(nullable=false) private LocalDateTime createdAt=LocalDateTime.now();
}
