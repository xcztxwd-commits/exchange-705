package com.gtcfesk.exchange.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "verify_code")
public class VerifyCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(nullable = false, length = 128)
    private String email;

    @Column(nullable = false, length = 32)
    private String scene; // register / login / forget_password

    @Column(nullable = false, length = 16)
    private String code;

    @Column(nullable = false)
    private LocalDateTime expireAt;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}







