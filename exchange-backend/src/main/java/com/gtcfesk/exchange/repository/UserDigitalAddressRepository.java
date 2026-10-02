package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.UserDigitalAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserDigitalAddressRepository extends com.gtcfesk.exchange.tenant.TenantRepository<UserDigitalAddress, Long> {
    List<UserDigitalAddress> findByTenantIdAndUserId(Long tenantId, Long userId);
}



