package com.gtcfesk.exchange.entity;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Getter @Setter @Entity
@Table(name="deposit_credit_record", uniqueConstraints=@UniqueConstraint(name="uk_deposit_credit", columnNames="deposit_record_id"))
public class DepositCreditRecord {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="deposit_record_id",nullable=false) private Long depositRecordId;
 @Column(nullable=false) private Long userId;
 @Column(nullable=false,length=16) private String accountType;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal amountUsd;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal balanceBefore;
 @Column(nullable=false,precision=32,scale=16) private BigDecimal balanceAfter;
 @Column(nullable=false,length=24) private String operatorType;
 @Column(nullable=false) private Long operatorId;
 @Column(length=128) private String operatorName;
 @Column(nullable=false) private LocalDateTime creditedAt;
}
