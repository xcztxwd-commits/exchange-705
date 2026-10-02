package com.gtcfesk.exchange.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@org.hibernate.annotations.Persister(impl = com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Entity
@Table(name = "announcement")
public class Announcement extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "PUBLISHED"; // PUBLISHED, DRAFT, HIDDEN

    @Column(name = "countdown_seconds", nullable = false, columnDefinition = "int default 2")
    private Integer countdownSeconds = 2;

    @Column(name = "priority", nullable = false)
    private Integer priority = 0; // 优先级，数字越大越优先显示

    @Column(name = "language", length = 10, nullable = false)
    private String language = "en"; // 语言：en, zh-TW, zh-CN, fr, ja, ko, th, vi, id, es, pt, ar, tr, ru, de, it

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // User-facing date; never changes the creation audit timestamp or popup countdown.
    @Column(name = "display_at")
    private LocalDateTime displayAt;

    public LocalDateTime getDisplayAt() {
        return displayAt == null ? createdAt : displayAt;
    }

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}



