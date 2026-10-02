package com.gtcfesk.exchange.repository;
import com.gtcfesk.exchange.entity.BalanceAdjustment;
import com.gtcfesk.exchange.tenant.TenantRepository;
import java.util.Optional;
public interface BalanceAdjustmentRepository extends TenantRepository<BalanceAdjustment,Long>{
 // Current read after the caller's user lock avoids stale replay receipts under MySQL REPEATABLE_READ.
 @org.springframework.transaction.annotation.Transactional(readOnly=true)
 @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_READ)
 Optional<BalanceAdjustment> findByTenantIdAndRequestKey(Long tenantId,String key);
}
