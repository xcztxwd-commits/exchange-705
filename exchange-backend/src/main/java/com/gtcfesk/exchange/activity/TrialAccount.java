package com.gtcfesk.exchange.activity;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.math.BigDecimal;
@Getter @Setter @Entity @Table(name="trial_account")
public class TrialAccount {
 @Id private Long userId;
 @Version private long rowVersion;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal available=BigDecimal.ZERO;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal frozen=BigDecimal.ZERO;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal granted=BigDecimal.ZERO;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal consumed=BigDecimal.ZERO;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal profits=BigDecimal.ZERO;
}
