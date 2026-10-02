package com.gtcfesk.exchange.activity;
import javax.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter @Entity @Table(name="activity_material")
@org.hibernate.annotations.Persister(impl=com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
public class ActivityMaterial extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @Column(nullable=false,length=80) private String name;
 @Column(nullable=false,columnDefinition="LONGTEXT") private String nodesJson;
 private boolean deleted;
}
