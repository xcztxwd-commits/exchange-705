package com.gtcfesk.exchange.repository;
import com.gtcfesk.exchange.entity.DepositCreditRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface DepositCreditRecordRepository extends JpaRepository<DepositCreditRecord,Long> {
 Optional<DepositCreditRecord> findByDepositRecordId(Long id);
}
