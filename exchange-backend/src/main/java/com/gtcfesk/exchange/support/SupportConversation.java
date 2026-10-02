package com.gtcfesk.exchange.support;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.Instant;

@org.hibernate.annotations.Persister(impl = com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Entity @Getter @Setter
@Table(name = "support_conversation", indexes = {
    @Index(name = "support_queue", columnList = "status,id"),
    @Index(name = "support_owner", columnList = "adminId,status"),
    @Index(name = "support_user", columnList = "userId,id")})
public class SupportConversation extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    private Long controlActorId;
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long userId;
    // NULL after closing; database uniqueness prevents duplicate active sessions across instances.
    @Column() private Long activeUserId;
    private Long adminId;
    @Column(nullable = false, length = 16) private String status = "WAITING";
    @Column(nullable = false, length = 64) private String clientIp;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    private Instant acceptedAt;
    private Instant closedAt;
    @Column(name="legal_hold", nullable=false) private boolean legalHold;
    @Column(nullable = false) private Instant updatedAt = Instant.now();
    private long userReadId;
    private long adminReadId;
    @Column(length = 64) private String lastHash = "";
}
