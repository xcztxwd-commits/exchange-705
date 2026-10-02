package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.AdminRoleMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface AdminRoleMenuRepository extends com.gtcfesk.exchange.tenant.TenantRepository<AdminRoleMenu, Long> {
    List<AdminRoleMenu> findByTenantIdAndRoleId(Long tenantId, Long roleId);
    
    @Modifying
    @Transactional
    @org.springframework.data.jpa.repository.Query("DELETE FROM AdminRoleMenu rm WHERE rm.tenantId = ?#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND (rm.roleId = ?1)")
    void deleteByRoleId(Long roleId);
}

