package com.gtcfesk.exchange.control;
import javax.persistence.*;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Entity
@Table(name="control_admin")
public class ControlAdmin {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,unique=true,length=64) private String account;
 @JsonIgnore @Column(nullable=false,length=128) private String passwordHash;
 @Column(nullable=false) private boolean enabled=true;
 @JsonIgnore @Column(columnDefinition="TEXT") private String mfaSecret;
 @Column(nullable=false) private boolean mfaEnabled;
 @JsonIgnore @Column(nullable=false) private long sessionVersion;
 @Version @JsonIgnore private long rowVersion;
}
