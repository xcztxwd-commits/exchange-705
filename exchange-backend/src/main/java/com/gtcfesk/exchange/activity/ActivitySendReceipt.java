package com.gtcfesk.exchange.activity;
import com.gtcfesk.exchange.tenant.TenantOwnedEntity;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
@Getter @Setter @Entity @org.hibernate.annotations.Persister(impl=com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Table(name="activity_send_receipt",uniqueConstraints=@UniqueConstraint(name="uk_activity_send_operation",columnNames={"tenant_id","campaign_id","operation_id"}))
public class ActivitySendReceipt extends TenantOwnedEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="campaign_id",nullable=false) private Long campaignId;
 @Column(name="operation_id",nullable=false,length=64) private String operationId;
 @Column(name="payload_hash",nullable=false,length=64) private String payloadHash;
 @Column(name="result_json",nullable=false,length=1000) private String resultJson;
}
