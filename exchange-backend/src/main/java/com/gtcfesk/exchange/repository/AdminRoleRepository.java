package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.AdminRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AdminRoleRepository extends com.gtcfesk.exchange.tenant.TenantRepository<AdminRole, Long> {
    Optional<AdminRole> findByTenantIdAndRoleCode(Long tenantId, String roleCode);
    List<AdminRole> findByTenantIdAndStatus(Long tenantId, String status);
    boolean existsByTenantIdAndRoleCode(Long tenantId, String roleCode);
}

