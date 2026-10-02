package com.gtcfesk.exchange.support;

import lombok.Getter;
import lombok.Setter;
import javax.persistence.*;
import java.time.Instant;

@org.hibernate.annotations.Persister(impl = com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Entity @Getter @Setter
@Table(name = "support_message", uniqueConstraints = @UniqueConstraint(columnNames={"tenant_id", "conversationId", "sender", "senderId", "requestId"}),
    indexes = @Index(name = "support_message_cursor", columnList = "conversationId,id"))
public class SupportMessage extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, updatable = false) private Long conversationId;
    @Column(nullable = false, updatable = false, length = 12) private String sender;
    @Column(nullable = false, updatable = false) private Long senderId;
    @Column(nullable = false, updatable = false, length = 128) private String senderName;
    @Column(nullable = false, updatable = false, length = 64) private String requestId;
    @Column(nullable = false, updatable = false, length = 4000) private String text;
    @Column(nullable = false, updatable = false) private boolean image;
    @Column(nullable = false, updatable = false, length = 64) private String imageHash;
    // Whole seconds keep canonical hashes stable even on databases with second-precision datetime columns.
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    @Column(nullable = false, updatable = false, length = 64) private String previousHash;
    @Column(nullable = false, updatable = false, length = 64) private String hash;
}
