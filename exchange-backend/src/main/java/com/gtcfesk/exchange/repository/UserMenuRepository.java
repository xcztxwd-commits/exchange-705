package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.UserMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserMenuRepository extends com.gtcfesk.exchange.tenant.TenantRepository<UserMenu, Long> {
    List<UserMenu> findByTenantIdAndUserId(Long tenantId, Long userId);
    void deleteByTenantIdAndUserId(Long tenantId, Long userId);
    void deleteByTenantIdAndUserIdAndMenuId(Long tenantId, Long userId, Long menuId);
    boolean existsByTenantIdAndUserIdAndMenuId(Long tenantId, Long userId, Long menuId);
}

