package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.UserAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface UserActionRepository extends com.gtcfesk.exchange.tenant.TenantRepository<UserAction, Long> {
    List<UserAction> findByTenantIdAndUserId(Long tenantId, Long userId);
    List<UserAction> findByTenantIdAndUserIdAndMenuId(Long tenantId, Long userId, Long menuId);
    boolean existsByTenantIdAndUserIdAndMenuIdAndActionCode(Long tenantId, Long userId, Long menuId, String actionCode);
    
    @Modifying
    @Transactional
    @org.springframework.data.jpa.repository.Query("DELETE FROM UserAction ua WHERE ua.tenantId = ?#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND (ua.userId = ?1)")
    void deleteByUserId(Long userId);
    
    @Modifying
    @Transactional
    @org.springframework.data.jpa.repository.Query("DELETE FROM UserAction ua WHERE ua.tenantId = ?#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND (ua.userId = ?1 AND ua.menuId = ?2)")
    void deleteByUserIdAndMenuId(Long userId, Long menuId);
}




