package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "user_account")
public class UserAccount {
    @Id
    @GeneratedValue(generator = "custom-user-id-generator")
    @org.hibernate.annotations.GenericGenerator(
        name = "custom-user-id-generator",
        strategy = "com.gtcfesk.exchange.config.CustomUserIdGenerator"
    )
    private Long id;

    @Column(unique = true, nullable = false, length = 128)
    private String email;

    @Column(length = 32)
    private String countryCode;

    @Column(unique = true, length = 32)
    private String phone;

    @Column(nullable = false, length = 128)
    private String passwordHash;

    @Column(length = 50)
    private String nickname;

    @Column(name = "invite_code", length = 32)
    private String inviteCode; // 注册时使用的邀请码（上级的邀请码）

    @Column(name = "my_invite_code", unique = true, length = 32)
    private String myInviteCode; // 用户自己的邀请码（用于邀请别人）

    @Column(name = "parent_user_id")
    private Long parentUserId; // 上级用户ID

    @Column(name = "status", nullable = false, length = 20)
    private String status = "normal"; // normal, frozen, banned

    @Column(name = "user_type", length = 20)
    private String userType = "normal"; // normal, agent

    @Column(name = "kyc_level")
    private Integer kycLevel = 0;

    @Column(name = "kyc_status", length = 20)
    private String kycStatus = "NOT_VERIFIED"; // NOT_VERIFIED, VERIFIED

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "last_login_ip", length = 64)
    private String lastLoginIp; // 最后登录IP

    @Column(name = "last_login_region", length = 128)
    private String lastLoginRegion; // 最后登录地区

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt; // 最后登录时间

    @Column(name = "last_login_domain", length = 255)
    private String lastLoginDomain; // 最后登录域名

    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt; // 最后活动时间（用于实时在线检测）

    @Column(name = "current_token", length = 128)
    private String currentToken; // 当前有效的登录token标识（用于单设备登录）

    @Column(name = "remark", length = 500)
    private String remark; // 备注（用于代理管理）

    // ==== 仅用于接口展示的临时字段（不入库） ====
    @Transient
    private BigDecimal fundBalance;

    @Transient
    private BigDecimal contractBalance;

    @Transient
    private BigDecimal optionBalance;

    @Transient
    private String parentUserEmail; // 上级用户邮箱（仅用于展示）

    @Transient
    private Integer subordinateCount; // 下级用户数量（仅用于展示）

    @Transient
    private BigDecimal usdtBalance; // USDT余额（仅用于展示，映射自fundBalance）

    @Transient
    private BigDecimal cnyBalance; // CNY余额（仅用于展示）

    @Transient
    private String userTypeLabel; // 用户类型标签：真人/假人（仅用于展示）

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

