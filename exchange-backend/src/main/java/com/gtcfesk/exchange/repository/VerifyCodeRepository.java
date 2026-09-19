package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.VerifyCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VerifyCodeRepository extends JpaRepository<VerifyCode, Long> {
    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<VerifyCode> findTopByEmailAndSceneOrderByIdDesc(String email, String scene);
}







