package com.gtcfesk.exchange.support;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.Instant;

@org.hibernate.annotations.Persister(impl = com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Entity @Getter @Setter
@Table(name = "inbox_letter", uniqueConstraints = {@UniqueConstraint(columnNames={"tenant_id", "adminId", "requestId", "userId"}), @UniqueConstraint(columnNames={"tenant_id", "controlActorId", "requestId", "userId"})},
    indexes = @Index(name = "inbox_recipient", columnList = "userId,id"))
public class InboxLetter extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, updatable = false) private Long userId;
    @Column(updatable = false) private Long adminId;
    @Column(updatable = false) private Long controlActorId;
    @Column(nullable = false, updatable = false, length = 64) private String requestId;
    @Column(nullable = false, updatable = false, length = 120) private String title;
    @Column(nullable = false, updatable = false, length = 4000) private String content;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    private Instant readAt;
}
