package com.gtcfesk.exchange.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminUserRepository extends com.gtcfesk.exchange.tenant.TenantRepository<AdminUser, Long> {
    Optional<AdminUser> findByTenantIdAndAccount(Long tenantId, String account);
    Optional<AdminUser> findByTenantIdAndEmail(Long tenantId, String email);
}







