package com.gtcfesk.exchange.activity;
import com.gtcfesk.exchange.tenant.TenantOwnedEntity;
import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
@Getter @Setter @Entity @org.hibernate.annotations.Persister(impl=com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Table(name="activity_selection_member",uniqueConstraints=@UniqueConstraint(name="uk_activity_selection_member",columnNames={"tenant_id","selection_id","user_id"}),indexes=@Index(name="ix_activity_selection_cursor",columnList="tenant_id,selection_id,id"))
public class ActivitySelectionMember extends TenantOwnedEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(name="selection_id",nullable=false) private Long selectionId;
 @Column(name="user_id",nullable=false) private Long userId;
}
