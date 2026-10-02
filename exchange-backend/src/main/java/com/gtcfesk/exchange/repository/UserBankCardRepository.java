package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.UserBankCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserBankCardRepository extends com.gtcfesk.exchange.tenant.TenantRepository<UserBankCard, Long> {
    List<UserBankCard> findByTenantIdAndUserId(Long tenantId, Long userId);
}



