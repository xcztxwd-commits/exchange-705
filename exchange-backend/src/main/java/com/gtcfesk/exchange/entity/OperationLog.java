package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "operation_log", indexes = {
    @Index(name = "idx_admin_id", columnList = "admin_id"),
    @Index(name = "idx_operation_type", columnList = "operation_type"),
    @Index(name = "idx_created_at", columnList = "created_at"),
    @Index(name = "idx_target_type_id", columnList = "target_type,target_id")
})
public class OperationLog {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "admin_id", nullable = false)
    private Long adminId;
    
    @Column(name = "admin_email", length = 100)
    private String adminEmail;
    
    @Column(name = "operation_type", nullable = false, length = 50)
    private String operationType; // 操作类型（如：用户管理、订单管理、充值审核等）
    
    @Column(name = "operation_action", nullable = false, length = 100)
    private String operationAction; // 操作动作（如：重置密码、审核通过、删除等）
    
    @Column(name = "target_type", length = 50)
    private String targetType; // 目标类型（如：用户、订单、充值记录等）
    
    @Column(name = "target_id")
    private Long targetId; // 目标ID
    
    @Column(name = "target_info", length = 500)
    private String targetInfo; // 目标信息（如：用户邮箱、订单号等）
    
    @Column(name = "request_method", length = 10)
    private String requestMethod; // 请求方法
    
    @Column(name = "request_url", length = 500)
    private String requestUrl; // 请求URL
    
    @Column(name = "request_params", columnDefinition = "TEXT")
    private String requestParams; // 请求参数（JSON格式）
    
    @Column(name = "ip_address", length = 50)
    private String ipAddress; // IP地址
    
    @Column(name = "user_agent", length = 500)
    private String userAgent; // 用户代理
    
    @Column(name = "status", length = 20)
    private String status = "SUCCESS"; // 操作状态（SUCCESS、FAILED）
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage; // 错误信息
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }
}




