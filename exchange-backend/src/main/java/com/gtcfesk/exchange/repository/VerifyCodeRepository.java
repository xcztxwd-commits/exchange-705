package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.VerifyCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VerifyCodeRepository extends com.gtcfesk.exchange.tenant.TenantRepository<VerifyCode, Long> {
    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<VerifyCode> findTopByTenantIdAndEmailAndSceneOrderByIdDesc(Long tenantId, String email, String scene);
}







