package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.AssetAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssetAccountRepository extends com.gtcfesk.exchange.tenant.TenantRepository<AssetAccount, Long> {

    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT a FROM AssetAccount a WHERE a.tenantId = :#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND (a.userId = :userId) ORDER BY a.coin, a.id")
    List<AssetAccount> lockByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);

    List<AssetAccount> findByTenantIdAndUserId(Long tenantId, Long userId);

    Optional<AssetAccount> findByTenantIdAndUserIdAndCoin(Long tenantId, Long userId, String coin);
}




