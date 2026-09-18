package com.gtcfesk.exchange.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "announcement")
public class Announcement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "PUBLISHED"; // PUBLISHED, DRAFT, HIDDEN

    @Column(name = "priority", nullable = false)
    private Integer priority = 0; // 优先级，数字越大越优先显示

    @Column(name = "language", length = 10, nullable = false)
    private String language = "en"; // 语言：en, zh-TW, zh-CN, fr, ja, ko, th, vi, id, es, pt, ar, tr, ru, de, it

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

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



