package com.gtcfesk.exchange.activity;
import com.gtcfesk.exchange.tenant.TenantOwnedEntity;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.LocalDateTime;
@Getter @Setter @Entity @org.hibernate.annotations.Persister(impl=com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Table(name="activity_selection",uniqueConstraints=@UniqueConstraint(name="uk_activity_selection_operation",columnNames={"tenant_id","campaign_id","operation_id"}))
public class ActivitySelection extends TenantOwnedEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Version private long rowVersion;
 @Column(name="campaign_id",nullable=false) private Long campaignId;
 @Column(name="operation_id",nullable=false,length=64) private String operationId;
 @Column(name="filter_hash",nullable=false,length=64) private String filterHash;
 @Column(name="send_operation_id",length=64) private String sendOperationId;
 @Column(name="created_at",nullable=false) private LocalDateTime createdAt=LocalDateTime.now();
 @Column(name="selected_count",nullable=false) private long selected;
 @Column(name="sent_count",nullable=false) private long sent;
 @Column(name="duplicate_count",nullable=false) private long duplicates;
 @Column(name="ineligible_count",nullable=false) private long ineligible;
 @Column(name="cursor_id",nullable=false) private long cursorId;
 @Column(nullable=false) private boolean done;
}
