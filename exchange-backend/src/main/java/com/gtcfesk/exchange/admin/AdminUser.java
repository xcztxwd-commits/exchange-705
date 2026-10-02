package com.gtcfesk.exchange.admin;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

@Getter
@Setter
@org.hibernate.annotations.Persister(impl = com.gtcfesk.exchange.tenant.TenantEntityPersister.class)
@Entity
@Table(name = "admin_user")
public class AdminUser extends com.gtcfesk.exchange.tenant.TenantOwnedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "current_token", length = 128)
    private String currentToken;

    @Column(nullable = false, length = 64)
    private String account;

    @Column(nullable = false, length = 128)
    private String email;

    @Column(nullable = false, length = 128)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String passwordHash;

    @Column(nullable = false, length = 32)
    private String role; // super_admin / admin / ops

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(nullable = false)
    private boolean mustChangePassword;

    private LocalDateTime createdAt;
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







