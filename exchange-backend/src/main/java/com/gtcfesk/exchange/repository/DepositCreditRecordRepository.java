package com.gtcfesk.exchange.repository;
import com.gtcfesk.exchange.entity.DepositCreditRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface DepositCreditRecordRepository extends com.gtcfesk.exchange.tenant.TenantRepository<DepositCreditRecord, Long> {
 Optional<DepositCreditRecord> findByTenantIdAndDepositRecordId(Long tenantId, Long id);
}
