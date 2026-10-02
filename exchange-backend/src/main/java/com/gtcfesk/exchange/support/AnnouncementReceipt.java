package com.gtcfesk.exchange.support;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.LocalDateTime;

/** Only the per-user receipt is stored; announcement content remains a single public row. */
@Entity @Getter @Setter
@org.hibernate.annotations.Persister(impl = com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Table(name = "announcement_receipt", uniqueConstraints = @UniqueConstraint(name = "uk_announcement_receipt", columnNames = {"tenant_id", "userId", "announcementId"}))
public class AnnouncementReceipt extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, updatable = false) private Long userId;
    @Column(nullable = false, updatable = false) private Long announcementId;
    @Column(nullable = false, updatable = false) private LocalDateTime readAt;
}
